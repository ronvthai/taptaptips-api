package com.taptaptips.server.service

import com.taptaptips.server.domain.AppSetting
import com.taptaptips.server.repo.AppSettingRepository
import org.slf4j.LoggerFactory
import org.springframework.core.env.Environment
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

/**
 * Settings you can change from the admin page (/admin) without a redeploy.
 *
 * Where a value comes from, first match wins:
 *   1. A row in app_setting (set from the admin page)
 *   2. The Render env var with the same name (e.g. REFERRAL_ENABLED)
 *   3. The built-in fallback below
 *
 * Values are cached for [TTL], so a change takes effect within ~30 seconds
 * everywhere (immediately on the instance that saved it).
 *
 * Secrets (Stripe keys, DB password, Firebase) stay in Render on purpose.
 */
@Service
class RuntimeSettings(
    private val repo: AppSettingRepository,
    private val env: Environment
) {
    enum class Type { BOOL, CENTS, NUMBER, URL }

    data class Def(
        val key: String,
        val type: Type,
        val group: String,
        val label: String,
        val help: String,
        val fallback: String,
        val min: Long? = null,
        val max: Long? = null
    )

    data class View(
        val key: String,
        val type: Type,
        val group: String,
        val label: String,
        val help: String,
        val value: String,
        val defaultValue: String,
        val overridden: Boolean,
        val min: Long?,
        val max: Long?,
        val updatedAt: Instant?,
        val updatedBy: String?
    )

    companion object {
        const val REFERRAL_ENABLED = "referral.enabled"
        const val REFERRAL_PAYOUTS_PAUSED = "referral.payoutsPaused"
        const val REFERRAL_REWARD_CENTS = "referral.rewardCents"
        const val REFERRAL_MONTHLY_CAP = "referral.monthlyCap"
        const val REFERRAL_MIN_TIP_CENTS = "referral.minTipCents"
        const val REFERRAL_APP_STORE_URL = "referral.appStoreUrl"
        const val GUEST_ENABLED = "tips.guest.enabled"
        const val GUEST_MIN_CENTS = "tips.guest.minCents"
        const val GUEST_MAX_CENTS = "tips.guest.maxCents"
        const val GUEST_PER_IP = "tips.guest.perIpPer10Min"
        const val GUEST_PER_RECEIVER = "tips.guest.perReceiverPerHour"

        val DEFS: List<Def> = listOf(
            Def(REFERRAL_ENABLED, Type.BOOL, "Referral program", "Referral program on",
                "Off: no new codes can be used. Referrals already in progress still finish.", "false"),
            Def(REFERRAL_PAYOUTS_PAUSED, Type.BOOL, "Referral program", "Pause referral payouts",
                "Rewards are still earned and saved, and paid out when you unpause.", "false"),
            Def(REFERRAL_REWARD_CENTS, Type.CENTS, "Referral program", "Reward per referral",
                "Applies to new referrals. Ones already started keep their amount. " +
                    "Can't be more than the minimum first tip.", "500", 0, 10_000),
            Def(REFERRAL_MIN_TIP_CENTS, Type.CENTS, "Referral program", "Minimum first tip to qualify",
                "A friend's first tip must be at least this much to earn the reward. " +
                    "Keep it at or above the reward so fake sign-ups don't pay off.", "500", 100, 100_000),
            Def(REFERRAL_MONTHLY_CAP, Type.NUMBER, "Referral program", "Max rewards per person (30 days)",
                "Extra referrals past this are recorded but not paid.", "50", 0, 1_000),
            Def(REFERRAL_APP_STORE_URL, Type.URL, "Referral program", "App Store link",
                "Used by the iPhone button on the invite page.", "https://apps.apple.com/app/id6764665388"),

            Def(GUEST_ENABLED, Type.BOOL, "Tip by link (no app)", "Tip by link on",
                "Off: tip pages (QR / NFC / link) say tipping isn't available right now.", "true"),
            Def(GUEST_MIN_CENTS, Type.CENTS, "Tip by link (no app)", "Minimum tip",
                "Smallest tip allowed on the tip page.", "100", 50, 10_000),
            Def(GUEST_MAX_CENTS, Type.CENTS, "Tip by link (no app)", "Maximum tip",
                "Largest tip allowed on the tip page.", "20000", 100, 100_000),
            Def(GUEST_PER_IP, Type.NUMBER, "Tip by link (no app)", "Tips per visitor (10 min)",
                "Limit per internet address, to stop card testing.", "10", 1, 1_000),
            Def(GUEST_PER_RECEIVER, Type.NUMBER, "Tip by link (no app)", "Tips per receiver (1 hour)",
                "Limit per person being tipped.", "60", 1, 10_000)
        )

        private val BY_KEY = DEFS.associateBy { it.key }
        private val TTL: Duration = Duration.ofSeconds(30)
        private val APP_STORE_REGEX =
            Regex("^https://(apps|itunes)\\.apple\\.com/[A-Za-z0-9/_.-]*id[0-9]+[A-Za-z0-9?=&_.-]*$")
    }

    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile private var cache: Map<String, AppSetting> = emptyMap()
    @Volatile private var loadedAt: Instant = Instant.EPOCH

    // ── Reading ──────────────────────────────────────────────────────────────

    fun bool(key: String): Boolean = raw(key).equals("true", ignoreCase = true)
    fun long(key: String): Long = raw(key).toLong()
    fun int(key: String): Int = long(key).toInt()
    fun string(key: String): String = raw(key)

    private fun raw(key: String): String {
        val def = BY_KEY[key] ?: error("Unknown setting $key")
        val override = overrides()[key]?.value
        if (override != null && validate(def, override) == null) return override
        return defaultFor(def)
    }

    /** Render env var if it's set and valid, otherwise the built-in fallback. */
    private fun defaultFor(def: Def): String {
        val fromEnv = env.getProperty(def.key)?.trim()?.trim('"', '\'')?.let { normalize(def, it) }
        return if (fromEnv != null && validate(def, fromEnv) == null) fromEnv else def.fallback
    }

    private fun overrides(): Map<String, AppSetting> {
        if (Duration.between(loadedAt, Instant.now()) > TTL) reload()
        return cache
    }

    @Synchronized
    fun reload() {
        if (Duration.between(loadedAt, Instant.now()) <= Duration.ofSeconds(1)) return
        try {
            cache = repo.findAll().associateBy { it.key }
        } catch (e: Exception) {
            // Keep the last good values; try again after the next TTL.
            log.warn("Couldn't load app settings, using cached values: ${e.message}")
        }
        loadedAt = Instant.now()
    }

    private fun forceReload() {
        loadedAt = Instant.EPOCH
        reload()
    }

    // ── Admin page ───────────────────────────────────────────────────────────

    fun list(): List<View> {
        val rows = overrides()
        return DEFS.map { d ->
            val row = rows[d.key]
            View(
                key = d.key, type = d.type, group = d.group, label = d.label, help = d.help,
                value = raw(d.key), defaultValue = defaultFor(d),
                overridden = row != null, min = d.min, max = d.max,
                updatedAt = row?.updatedAt, updatedBy = row?.updatedBy
            )
        }
    }

    /** Saves a new value. Throws IllegalArgumentException with a readable message if it's not allowed. */
    @Synchronized
    fun set(key: String, rawValue: String, by: String) {
        val def = BY_KEY[key] ?: throw IllegalArgumentException("Unknown setting.")
        val value = normalize(def, rawValue.trim())
            ?: throw IllegalArgumentException("That isn't a valid value.")
        validate(def, value)?.let { throw IllegalArgumentException(it) }

        // Referral reward ≤ minimum qualifying tip (otherwise fake sign-ups that
        // tip a stranger turn a profit).
        if (key == REFERRAL_REWARD_CENTS && value.toLong() > long(REFERRAL_MIN_TIP_CENTS))
            throw IllegalArgumentException("The reward can't be more than the minimum first tip. Raise that first.")
        if (key == REFERRAL_MIN_TIP_CENTS && value.toLong() < long(REFERRAL_REWARD_CENTS))
            throw IllegalArgumentException("The minimum first tip can't be less than the reward. Lower the reward first.")

        // Keep min ≤ max for tip limits.
        if (key == GUEST_MIN_CENTS && value.toLong() > long(GUEST_MAX_CENTS))
            throw IllegalArgumentException("Minimum can't be more than the maximum tip.")
        if (key == GUEST_MAX_CENTS && value.toLong() < long(GUEST_MIN_CENTS))
            throw IllegalArgumentException("Maximum can't be less than the minimum tip.")

        val row = repo.findById(key).orElse(null) ?: AppSetting(key = key, value = value)
        row.value = value
        row.updatedAt = Instant.now()
        row.updatedBy = by.take(100)
        repo.save(row)
        log.info("⚙️ Setting $key = $value (by $by)")
        forceReload()
    }

    /** Removes the override, going back to the Render env var / built-in default. */
    @Synchronized
    fun reset(key: String, by: String) {
        if (key !in BY_KEY) throw IllegalArgumentException("Unknown setting.")
        if (repo.existsById(key)) repo.deleteById(key)
        log.info("⚙️ Setting $key reset to default (by $by)")
        forceReload()
    }

    private fun show(def: Def, n: Long): String =
        if (def.type == Type.CENTS) "\$" + String.format("%.2f", n / 100.0) else n.toString()

    private fun normalize(def: Def, v: String): String? = when (def.type) {
        Type.BOOL -> when (v.lowercase()) {
            "true", "on", "yes", "1" -> "true"
            "false", "off", "no", "0" -> "false"
            else -> null
        }
        Type.CENTS, Type.NUMBER -> v.toLongOrNull()?.toString()
        Type.URL -> v
    }

    private fun validate(def: Def, v: String): String? = when (def.type) {
        Type.BOOL -> if (v == "true" || v == "false") null else "Must be on or off."
        Type.CENTS, Type.NUMBER -> {
            val n = v.toLongOrNull()
            when {
                n == null -> "Must be a whole number."
                def.min != null && n < def.min -> "Must be at least ${show(def, def.min)}."
                def.max != null && n > def.max -> "Must be at most ${show(def, def.max)}."
                else -> null
            }
        }
        Type.URL -> if (def.key == REFERRAL_APP_STORE_URL && !APP_STORE_REGEX.matches(v))
            "Must be an App Store link like https://apps.apple.com/app/id123456789"
        else if (!v.startsWith("https://") || v.length > 500) "Must be an https:// link." else null
    }
}
