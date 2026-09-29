package com.taptaptips.server.service

import com.taptaptips.server.domain.BlockedTerm
import com.taptaptips.server.domain.DisplayNameFlag
import com.taptaptips.server.domain.TermAction
import com.taptaptips.server.domain.TermMatch
import com.taptaptips.server.repo.BlockedTermRepository
import com.taptaptips.server.repo.DisplayNameFlagRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.io.ClassPathResource
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.text.Normalizer
import java.util.UUID

/**
 * Word/phrase filter for display names.
 *
 * The list lives in the `blocked_term` table (seeded from
 * resources/moderation/blocked-terms.txt the first time the table is empty)
 * and is reloaded every 5 minutes, so rows you add in pgAdmin take effect
 * without a deploy.
 *
 * Before matching, names are normalized so the common tricks don't work:
 * "F.U.C.K", "fuuuck", "Ƒuck" (look-alike letters), "a$$", "b a d".
 */
@Service
class DisplayNameModerator(
    private val terms: BlockedTermRepository,
    private val flags: DisplayNameFlagRepository
) {
    private val log = LoggerFactory.getLogger(javaClass)

    sealed class Result {
        object Ok : Result()
        /** Accept the name, but record it for review. */
        data class Review(val term: String) : Result()
        /** Reject the name. */
        data class Blocked(val term: String) : Result()
    }

    /** Shown to users. Deliberately doesn't say which word matched. */
    val rejectionMessage = "That name isn't allowed. Please choose a different one."

    private data class CompiledTerm(
        val original: String,
        val action: TermAction,
        val match: TermMatch,
        /**
         * Pattern built from the normalized term where each letter may repeat:
         * "fuck" → f+u+c+k+ (catches "fuuuck"), "ass" → a+s{2,} (so "as"
         * and "bob" never match "ass" and "boob").
         */
        val pattern: Regex,
        val isPhrase: Boolean
    )

    @Volatile private var compiled: List<CompiledTerm> = emptyList()
    @Volatile private var allowWords: Set<String> = emptySet()

    // ─────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────

    fun check(displayName: String): Result {
        val list = compiled
        if (list.isEmpty()) return Result.Ok

        val variants = variantsOf(displayName)
        var review: String? = null

        for (t in list) {
            if (t.action == TermAction.ALLOW) continue
            val hit = variants.any { v -> matches(t, v) }
            if (!hit) continue
            if (t.action == TermAction.BLOCK) return Result.Blocked(t.original)
            if (review == null) review = t.original
        }
        return review?.let { Result.Review(it) } ?: Result.Ok
    }

    /** Record a REVIEW match after the user has been saved. Never throws. */
    fun flag(userId: UUID, displayName: String, term: String) {
        try {
            flags.save(DisplayNameFlag(userId = userId, displayName = displayName.take(100), matchedTerm = term))
            log.warn("📝 Display name flagged for review: user=$userId term='$term'")
        } catch (e: Exception) {
            log.error("Failed to save display name flag for $userId", e)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Loading
    // ─────────────────────────────────────────────────────────────────────

    @EventListener(ApplicationReadyEvent::class)
    fun onStartup() {
        seedIfEmpty()
        reload()
    }

    @Scheduled(fixedDelay = 5 * 60_000L, initialDelay = 5 * 60_000L)
    fun reload() {
        try {
            val all = terms.findAll()
            allowWords = all.filter { it.action == TermAction.ALLOW }
                .map { normalizeWord(it.term) }
                .toSet()
            compiled = all.mapNotNull { compile(it) }
            log.info("🧹 Display name filter loaded: ${compiled.size} terms (${allowWords.size} allowed)")
        } catch (e: Exception) {
            // Keep the previous list rather than silently disabling the filter.
            log.error("Failed to reload display name filter — keeping previous list", e)
        }
    }

    private fun seedIfEmpty() {
        try {
            if (terms.count() > 0) return
            val resource = ClassPathResource("moderation/blocked-terms.txt")
            if (!resource.exists()) {
                log.warn("moderation/blocked-terms.txt not found — display name filter starts empty")
                return
            }
            val rows = resource.inputStream.bufferedReader().readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .mapNotNull { parseSeedLine(it) }
                .distinctBy { it.term }
            terms.saveAll(rows)
            log.info("🧹 Seeded ${rows.size} display name filter terms")
        } catch (e: Exception) {
            log.error("Failed to seed display name filter", e)
        }
    }

    /** Format: term | BLOCK/REVIEW/ALLOW | WORD/CONTAINS | note  (last three optional) */
    private fun parseSeedLine(line: String): BlockedTerm? {
        val parts = line.split("|").map { it.trim() }
        val term = parts[0].lowercase()
        if (term.isEmpty()) return null
        val action = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { TermAction.valueOf(it.uppercase()) }.getOrNull() } ?: TermAction.BLOCK
        val match = parts.getOrNull(2)?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { TermMatch.valueOf(it.uppercase()) }.getOrNull() } ?: TermMatch.WORD
        return BlockedTerm(term = term, action = action, matchType = match, note = parts.getOrNull(3))
    }

    private fun compile(t: BlockedTerm): CompiledTerm? {
        val words = tokens(normalizeBase(t.term)).map { lettersOnly(it) }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        val joined = words.joinToString("")
        return CompiledTerm(
            original = t.term,
            action = t.action,
            match = t.matchType,
            pattern = Regex(repeatTolerant(joined)),
            isPhrase = words.size > 1
        )
    }

    /** "ass" → "a+s{2,}", "fuck" → "f+u+c+k+". */
    private fun repeatTolerant(term: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < term.length) {
            val c = term[i]
            var n = 1
            while (i + n < term.length && term[i + n] == c) n++
            sb.append(Regex.escape(c.toString()))
            sb.append(if (n == 1) "+" else "{$n,}")
            i += n
        }
        return sb.toString()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Matching
    // ─────────────────────────────────────────────────────────────────────

    /** One normalized reading of a name: its words, plus all words joined. */
    private data class Variant(val words: List<String>, val joined: String)

    private fun matches(t: CompiledTerm, v: Variant): Boolean = when {
        // Phrases ("cash app") are matched against the joined name, so
        // "CashApp", "cash-app" and "Send me cash app" are all caught.
        t.isPhrase -> t.pattern.containsMatchIn(v.joined)
        t.match == TermMatch.WORD -> v.words.any { t.pattern.matches(it) }
        // CONTAINS checks inside each word only — checking the joined name
        // would block innocent pairs like "Jeff Uckers".
        else -> v.words.any { t.pattern.containsMatchIn(it) }
    }

    /**
     * Two readings of the name: with look-alike digits/symbols translated
     * ("a55" → "ass") and without (so "Suite 455" isn't read as a word).
     * Allow-listed words are removed from both.
     */
    private fun variantsOf(name: String): List<Variant> {
        val base = normalizeBase(name)
        val plain = mergeSingleLetters(tokens(base))
        val leet = mergeSingleLetters(tokens(base).map { if (it.any(Char::isLetter)) deLeet(it) else it })
        return listOf(plain, leet).distinct().map { words ->
            val kept = words.map { lettersOnly(it) }.filter { it.isNotEmpty() && it !in allowWords }
            Variant(kept, kept.joinToString(""))
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Normalization
    // ─────────────────────────────────────────────────────────────────────

    /** Unicode cleanup, lowercase, accents removed, look-alike letters mapped. */
    private fun normalizeBase(s: String): String {
        val nfkc = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase()
        val noAccents = Normalizer.normalize(nfkc, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        val sb = StringBuilder(noAccents.length)
        for (c in noAccents) {
            if (c in INVISIBLE) continue
            sb.append(CONFUSABLES[c] ?: c)
        }
        return sb.toString()
    }

    private fun normalizeWord(s: String): String =
        tokens(normalizeBase(s)).joinToString("") { lettersOnly(it) }

    private fun lettersOnly(w: String): String = w.filter { it.isLetterOrDigit() }

    /** Split on anything that isn't a letter, digit or leet symbol. */
    private fun tokens(s: String): List<String> =
        s.split(Regex("[^\\p{L}\\p{N}@$!|]+")).filter { it.isNotEmpty() }

    /** "b a d" / "b.a.d" / "b-a-d" → "bad". */
    private fun mergeSingleLetters(words: List<String>): List<String> {
        val out = ArrayList<String>(words.size)
        val run = StringBuilder()
        for (w in words) {
            if (w.length == 1) { run.append(w); continue }
            if (run.isNotEmpty()) { out.add(run.toString()); run.clear() }
            out.add(w)
        }
        if (run.isNotEmpty()) out.add(run.toString())
        return out
    }

    private fun deLeet(w: String): String {
        val sb = StringBuilder(w.length)
        for (c in w) sb.append(LEET[c] ?: c)
        return sb.toString()
    }

    companion object {
        private val LEET = mapOf(
            '0' to 'o', '1' to 'i', '3' to 'e', '4' to 'a', '5' to 's',
            '7' to 't', '8' to 'b', '9' to 'g', '@' to 'a', '$' to 's',
            '!' to 'i', '|' to 'l'
        )

        /** Common Cyrillic/Greek letters that look identical to Latin ones. */
        private val CONFUSABLES = mapOf(
            'а' to 'a', 'в' to 'b', 'е' to 'e', 'ё' to 'e', 'к' to 'k', 'м' to 'm',
            'н' to 'h', 'о' to 'o', 'р' to 'p', 'с' to 'c', 'т' to 't', 'у' to 'y',
            'х' to 'x', 'і' to 'i', 'ј' to 'j', 'ѕ' to 's', 'ԁ' to 'd', 'ԛ' to 'q',
            'ԝ' to 'w', 'α' to 'a', 'β' to 'b', 'ε' to 'e', 'η' to 'n', 'ι' to 'i',
            'κ' to 'k', 'ν' to 'v', 'ο' to 'o', 'ρ' to 'p', 'τ' to 't', 'υ' to 'u',
            'χ' to 'x', 'ƒ' to 'f'
        )

        /** Zero-width and direction-control characters people use to split words. */
        private val INVISIBLE = setOf(
            '\u200B', '\u200C', '\u200D', '\u2060', '\uFEFF', '\u00AD',
            '\u202A', '\u202B', '\u202C', '\u202D', '\u202E',
            '\u2066', '\u2067', '\u2068', '\u2069'
        )
    }
}
