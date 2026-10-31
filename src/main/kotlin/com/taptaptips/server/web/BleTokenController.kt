package com.taptaptips.server.web

import com.taptaptips.server.repo.AppUserRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.jdbc.core.RowMapper
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.security.SecureRandom
import java.sql.Timestamp
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Issues and resolves opaque BLE tokens.
 *
 * Privacy design
 * ──────────────
 * BLE advertisements must never carry PII (email, UUID, display name).
 * Instead, each logged-in user is assigned a short random hex token.
 * Nearby scanners resolve the token to a display name + avatar via
 * POST /ble/resolve — the raw user identity is never transmitted over the air.
 *
 * Token format: 8 lowercase hex characters (32 bits of entropy).
 *
 * Persistence (discovery-speed fix)
 * ─────────────────────────────────
 * Tokens used to live only in memory, so every server restart / Render deploy
 * wiped them. Phones that were in a pocket at the time (locked iPhones in
 * particular — they can't refresh until the user opens the app) kept
 * advertising a token nobody could resolve, and simply vanished from every
 * sender's nearby list. Tokens are now also written to a small `ble_token`
 * table: the in-memory maps stay as the fast path, the table is the source of
 * truth after a restart. Every DB call is best-effort — if the table is
 * unavailable we fall back to the old in-memory behaviour, never fail a request.
 *
 * The expiry is a sliding window (default 72 h, `ble.token-ttl-hours`). It used
 * to be 10 h, so a phone left untouched overnight was undiscoverable the next
 * morning until its owner opened the app.
 */
@RestController
@RequestMapping("/ble")
class BleTokenController(
    private val users: AppUserRepository,
    private val jdbc: JdbcTemplate,
    @Value("\${ble.token-ttl-hours:72}") private val tokenTtlHours: Long,
    /**
     * The TapTapTips bit(s) in Apple's background "overflow" advertisement, as
     * 32 hex chars. Android logs "BLE: learned iOS overflow bits <hex>
     * (trusted=true)" once it has confirmed two different iPhones; put that
     * value in BLE_IOS_OVERFLOW_MASK on Render and every Android install
     * recognises locked TapTapTips iPhones from the first packet — no app
     * release needed. Leave empty to keep learning on each device.
     */
    @Value("\${ble.ios-overflow-mask:\${BLE_IOS_OVERFLOW_MASK:}}") private val iosOverflowMask: String
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val rng = SecureRandom()

    /**
     * token  → userId mapping (fast path; backed by the ble_token table)
     * userId → token mapping (so a user always gets the same token until it expires)
     */
    private val tokenToUser = ConcurrentHashMap<String, TokenEntry>()
    private val userToToken = ConcurrentHashMap<String, String>()

    @Volatile private var dbReady = false

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ble-token-gc").apply { isDaemon = true }
    }

    init {
        // Purge expired entries every 5 minutes
        scheduler.scheduleAtFixedRate(::purgeExpired, 5, 5, TimeUnit.MINUTES)
    }

    data class TokenEntry(
        val userId: String,
        val expiresAt: Long          // System.currentTimeMillis()
    )

    // ── schema + warm cache ─────────────────────────────────────────────────

    @EventListener(ApplicationReadyEvent::class)
    fun ensureSchema() {
        try {
            jdbc.execute(
                """
                CREATE TABLE IF NOT EXISTS ble_token (
                    token      varchar(8)  PRIMARY KEY,
                    user_id    uuid        NOT NULL,
                    expires_at timestamptz NOT NULL
                )
                """.trimIndent()
            )
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_ble_token_user ON ble_token (user_id)")
            dbReady = true

            // Warm the in-memory maps so the first resolves after a restart
            // don't each need a DB round trip.
            var loaded = 0
            jdbc.query(
                "SELECT token, user_id, expires_at FROM ble_token WHERE expires_at > now()",
                RowCallbackHandler { rs ->
                val token = rs.getString("token")
                val userId = rs.getObject("user_id").toString()
                val exp = rs.getTimestamp("expires_at").time
                tokenToUser[token] = TokenEntry(userId, exp)
                userToToken[userId] = token
                loaded++
            })
            logger.info("✅ BLE token table ready — restored $loaded live token(s)")
        } catch (e: Exception) {
            dbReady = false
            logger.error("❌ BLE token table unavailable — tokens are in-memory only until fixed", e)
        }
    }

    // ── config ───────────────────────────────────────────────────────────────

    /**
     * GET /ble/config
     *
     * Discovery hints the apps can pick up without a release.
     */
    @GetMapping("/config")
    fun config(): BleConfigResponse {
        val mask = iosOverflowMask.trim().lowercase()
            .takeIf { it.length == 32 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
        return BleConfigResponse(iosOverflowMask = mask)
    }

    // ── issue ────────────────────────────────────────────────────────────────

    /**
     * POST /ble/token
     *
     * Returns the caller's current BLE token, issuing a new one if none exists
     * or the existing one has expired.  The token is stored on the phone and
     * placed in the BLE advertisement payload.
     */
    @PostMapping("/token")
    fun issueToken(): BleTokenResponse {
        val userId = authUserId().toString()
        val now = System.currentTimeMillis()
        val ttlMs = TimeUnit.HOURS.toMillis(tokenTtlHours)

        // Reuse if still valid — but slide the expiry forward so a logged-in
        // user is guaranteed a fresh `now + ttlMs` window of discoverability.
        // Token value stays the same, so senders who've already resolved this
        // peer keep working, and the phone doesn't have to restart advertising.
        val existing = userToToken[userId] ?: dbFindLiveTokenForUser(userId)
        if (existing != null) {
            val entry = tokenToUser[existing] ?: dbLookup(listOf(existing))[existing]
            if (entry != null && entry.expiresAt > now && entry.userId == userId) {
                val slid = entry.copy(expiresAt = now + ttlMs)
                tokenToUser[existing] = slid
                userToToken[userId] = existing
                dbSlide(existing, userId, slid.expiresAt)
                return BleTokenResponse(token = existing)
            }
        }

        // Generate fresh token, retry on (very unlikely) collision
        var token: String
        var attempts = 0
        while (true) {
            token = generateToken()
            attempts++
            if (attempts > 10) throw ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR, "Token generation failed"
            )
            if (tokenToUser.containsKey(token)) continue
            if (dbInsert(token, userId, now + ttlMs)) break
        }

        tokenToUser[token] = TokenEntry(userId, now + ttlMs)

        // Remove old token for this user if any
        val old = userToToken.put(userId, token)
        if (old != null && old != token) tokenToUser.remove(old)
        dbDeleteOtherTokens(userId, token)

        logger.debug("🔑 Issued BLE token for user ${userId.take(8)}…")
        return BleTokenResponse(token = token)
    }

    // ── resolve ──────────────────────────────────────────────────────────────

    /**
     * POST /ble/resolve
     *
     * Resolves one or more opaque BLE tokens to display-able user profiles.
     * Called by the scanner after it observes a token in a BLE advertisement.
     * No PII is in the token itself — resolution happens entirely server-side.
     *
     * Tokens that are unknown or expired are silently omitted from the response.
     */
    @PostMapping("/resolve")
    fun resolveTokens(@RequestBody request: BleResolveRequest): BleResolveResponse {
        if (request.tokens.isEmpty()) return BleResolveResponse(emptyList())
        if (request.tokens.size > 50) throw ResponseStatusException(
            HttpStatus.BAD_REQUEST, "Maximum 50 tokens per request"
        )

        val now = System.currentTimeMillis()
        val tokens = request.tokens.map { it.trim().lowercase() }.distinct()

        // Memory first; anything missing (e.g. issued before a restart that
        // the warm-up didn't catch, or by another instance) comes from the DB
        // in one query.
        val misses = tokens.filter { !tokenToUser.containsKey(it) }
        if (misses.isNotEmpty()) {
            dbLookup(misses).forEach { (t, e) ->
                tokenToUser[t] = e
                userToToken.putIfAbsent(e.userId, t)
            }
        }

        // Resolve token -> (still-valid) userId entries first, then fetch
        // every user in a single findAllById() call.
        val validEntries = tokens.mapNotNull { token ->
            val entry = tokenToUser[token] ?: return@mapNotNull null
            if (entry.expiresAt <= now) return@mapNotNull null        // expired
            val userId = try {
                UUID.fromString(entry.userId)
            } catch (e: Exception) { return@mapNotNull null }
            token to userId
        }

        if (validEntries.isEmpty()) return BleResolveResponse(emptyList())

        val usersById = users.findAllById(validEntries.map { it.second }.distinct())
            .associateBy { it.id }

        val results = validEntries.mapNotNull { (token, userId) ->
            val user = usersById[userId] ?: return@mapNotNull null
            BleTokenProfile(
                token       = token,
                userId      = user.id.toString(),
                displayName = user.displayName,
                hasAvatar   = user.profilePicture != null
            )
        }

        return BleResolveResponse(results)
    }

    // ── DB helpers (all best-effort) ─────────────────────────────────────────

    private val tokenFormat = Regex("^[0-9a-f]{8}$")

    private fun dbLookup(tokens: List<String>): Map<String, TokenEntry> {
        if (!dbReady) return emptyMap()
        val clean = tokens.filter { it.matches(tokenFormat) }
        if (clean.isEmpty()) return emptyMap()
        return try {
            val placeholders = clean.joinToString(",") { "?" }
            val out = HashMap<String, TokenEntry>()
            jdbc.query(
                "SELECT token, user_id, expires_at FROM ble_token " +
                        "WHERE token IN ($placeholders) AND expires_at > now()",
                RowCallbackHandler { rs ->
                    out[rs.getString("token")] = TokenEntry(
                        rs.getObject("user_id").toString(),
                        rs.getTimestamp("expires_at").time
                    )
                },
                *clean.toTypedArray()
            )
            out
        } catch (e: Exception) {
            logger.warn("BLE token DB lookup failed: ${e.message}")
            emptyMap()
        }
    }

    private fun dbFindLiveTokenForUser(userId: String): String? {
        if (!dbReady) return null
        return try {
            jdbc.query(
                "SELECT token FROM ble_token WHERE user_id = ? AND expires_at > now() " +
                        "ORDER BY expires_at DESC LIMIT 1",
                RowMapper { rs, _ -> rs.getString("token") },
                UUID.fromString(userId)
            ).firstOrNull()
        } catch (e: Exception) {
            logger.warn("BLE token DB user lookup failed: ${e.message}")
            null
        }
    }

    /** Insert a new token. False only on a token collision (caller retries). */
    private fun dbInsert(token: String, userId: String, expiresAt: Long): Boolean {
        if (!dbReady) return true
        return try {
            jdbc.update(
                "INSERT INTO ble_token (token, user_id, expires_at) VALUES (?, ?, ?) " +
                        "ON CONFLICT (token) DO NOTHING",
                token, UUID.fromString(userId), Timestamp(expiresAt)
            ) == 1
        } catch (e: Exception) {
            logger.warn("BLE token DB insert failed (continuing in-memory): ${e.message}")
            true
        }
    }

    /**
     * Extend a token's expiry — and re-create its row if it's missing (rows
     * deleted by hand, or issued while the table was unavailable). A plain
     * UPDATE left such tokens memory-only, so the next restart lost them.
     */
    private fun dbSlide(token: String, userId: String, expiresAt: Long) {
        if (!dbReady) return
        try {
            jdbc.update(
                "INSERT INTO ble_token (token, user_id, expires_at) VALUES (?, ?, ?) " +
                        "ON CONFLICT (token) DO UPDATE SET expires_at = EXCLUDED.expires_at",
                token, UUID.fromString(userId), Timestamp(expiresAt)
            )
        } catch (e: Exception) {
            logger.warn("BLE token DB slide failed: ${e.message}")
        }
    }

    private fun dbDeleteOtherTokens(userId: String, keep: String) {
        if (!dbReady) return
        try {
            jdbc.update(
                "DELETE FROM ble_token WHERE user_id = ? AND token <> ?",
                UUID.fromString(userId), keep
            )
        } catch (e: Exception) {
            logger.warn("BLE token DB cleanup failed: ${e.message}")
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun authUserId() = UUID.fromString(
        SecurityContextHolder.getContext().authentication.name
    )

    private fun generateToken(): String {
        val bytes = ByteArray(4)
        rng.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }   // 8 hex chars
    }

    private fun purgeExpired() {
        val now = System.currentTimeMillis()
        val expired = tokenToUser.entries.filter { it.value.expiresAt <= now }
        expired.forEach { (token, entry) ->
            tokenToUser.remove(token)
            userToToken.remove(entry.userId, token)
        }
        if (expired.isNotEmpty()) logger.debug("🧹 Purged ${expired.size} expired BLE tokens")
        if (dbReady) {
            try {
                jdbc.update("DELETE FROM ble_token WHERE expires_at <= now()")
            } catch (e: Exception) {
                logger.warn("BLE token DB purge failed: ${e.message}")
            }
        }
    }
}

// ── DTOs ─────────────────────────────────────────────────────────────────────

data class BleTokenResponse(val token: String)

data class BleResolveRequest(val tokens: List<String>)

data class BleTokenProfile(
    val token: String,
    val userId: String,
    val displayName: String,
    val hasAvatar: Boolean
)

data class BleResolveResponse(val results: List<BleTokenProfile>)

data class BleConfigResponse(val iosOverflowMask: String?)