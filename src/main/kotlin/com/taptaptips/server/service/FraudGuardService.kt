package com.taptaptips.server.service

import com.stripe.model.Account
import com.stripe.model.BankAccount
import com.stripe.model.Card
import com.stripe.param.AccountUpdateParams
import com.taptaptips.server.domain.AppUser
import com.taptaptips.server.domain.BannedIdentifier
import com.taptaptips.server.domain.UserSignal
import com.taptaptips.server.repo.AppUserRepository
import com.taptaptips.server.repo.BannedIdentifierRepository
import com.taptaptips.server.repo.UserSignalRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/**
 * Keeps banned people from coming back with a new account, using only what
 * the server already sees (no app update needed):
 *
 *  - BANK  payout bank / debit card fingerprint from Stripe. Checked whenever a
 *          connected account changes. Match → suspend + pause payouts + alert.
 *          This is the strong one: no bank account, no money out.
 *  - EMAIL normalized email. Checked at sign-up. Match → sign-up refused.
 *  - IP    sign-up and login address. Match → alert only by default, because
 *          shared Wi-Fi and phone carriers put innocent people on one address.
 *          The admin setting "Suspend sign-ups from banned addresses" makes it
 *          suspend instead.
 *
 * Also alerts when one bank account is linked to several TapTapTips users
 * (normal sharing is 1–2; more can mean one person running fake accounts).
 *
 * Every check fails OPEN: if the database or Stripe call fails we log it and
 * let the normal flow continue, so a bug here can't block real users from
 * signing up or logging in.
 *
 * Alerts go to the log (search "FRAUD MATCH") and, if APP_ALERT_EMAIL is set
 * in Render, by email.
 */
@Service
class FraudGuardService(
    private val banned: BannedIdentifierRepository,
    private val signals: UserSignalRepository,
    private val users: AppUserRepository,
    private val settings: RuntimeSettings,
    private val email: EmailService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val BANK = "BANK"
        const val EMAIL = "EMAIL"
        const val IP = "IP"
        val TYPES = setOf(BANK, EMAIL, IP)

        /** What a suspended user sees in the app. Neutral on purpose. */
        const val PUBLIC_SUSPENSION_REASON = "Violation of Terms of Service"

        /**
         * Lowercase, trim, drop "+anything" before the @, and for Gmail drop
         * dots and treat googlemail.com as gmail.com — so j.o.e+1@gmail.com
         * and joe@gmail.com are the same person.
         */
        fun normalizeEmail(raw: String): String {
            val e = raw.trim().lowercase()
            val at = e.lastIndexOf('@')
            if (at <= 0) return e
            var local = e.substring(0, at).substringBefore('+')
            var domain = e.substring(at + 1)
            if (domain == "googlemail.com") domain = "gmail.com"
            if (domain == "gmail.com") local = local.replace(".", "")
            return "$local@$domain"
        }
    }

    // ── Sign-up ──────────────────────────────────────────────────────────────

    data class SignupCheck(val refuse: Boolean, val ipMatch: BannedIdentifier?)

    /** Before the account is created. [SignupCheck.refuse] = don't create it. */
    fun checkSignup(rawEmail: String, ip: String): SignupCheck = try {
        val emailMatch = banned.findByTypeAndValue(EMAIL, normalizeEmail(rawEmail))
        if (emailMatch != null) {
            alert("Sign-up refused: banned email",
                "Email $rawEmail matches the ban list (from user ${emailMatch.sourceUserId}). IP: $ip")
            SignupCheck(refuse = true, ipMatch = null)
        } else {
            SignupCheck(refuse = false, ipMatch = banned.findByTypeAndValue(IP, ip))
        }
    } catch (e: Exception) {
        log.error("Fraud sign-up check failed (allowing sign-up): ${e.message}")
        SignupCheck(false, null)
    }

    /** After the account is created. */
    fun onRegistered(user: AppUser, ip: String, check: SignupCheck) {
        try {
            record(user.id, EMAIL, normalizeEmail(user.email))
            record(user.id, IP, ip)
            check.ipMatch?.let { onBannedIpSeen(user, ip, it, "signed up") }
        } catch (e: Exception) {
            log.error("Fraud sign-up recording failed for ${user.id}: ${e.message}")
        }
    }

    // ── Login ────────────────────────────────────────────────────────────────

    fun onLogin(user: AppUser, ip: String) {
        try {
            val isNew = record(user.id, IP, ip)
            if (!isNew || user.suspended) return      // only act the first time we see this address
            banned.findByTypeAndValue(IP, ip)?.let { onBannedIpSeen(user, ip, it, "logged in") }
        } catch (e: Exception) {
            log.error("Fraud login check failed for ${user.id}: ${e.message}")
        }
    }

    private fun onBannedIpSeen(user: AppUser, ip: String, match: BannedIdentifier, what: String) {
        val suspend = settings.bool(RuntimeSettings.FRAUD_SUSPEND_ON_IP_MATCH)
        if (suspend) suspendUser(user, "Banned IP $ip")
        alert(
            if (suspend) "Suspended: ${user.email} $what from a banned IP"
            else "Review: ${user.email} $what from a banned IP",
            "User ${user.id} (${user.email}, \"${user.displayName}\") $what from $ip, which belongs to " +
                "banned user ${match.sourceUserId}.\n" +
                (if (suspend) "The account was suspended automatically."
                 else "Nothing was blocked (shared Wi-Fi / carriers can share an address). " +
                      "If it's the same person, ban them on /admin.")
        )
    }

    // ── Stripe connected account changed ─────────────────────────────────────

    /**
     * Called from the Stripe webhook when a connected account changes.
     * Reads the payout bank accounts / debit cards fresh from Stripe and checks
     * their fingerprints against the ban list.
     */
    fun onStripeAccountChanged(user: AppUser) {
        val accountId = user.stripeAccountId ?: return
        val fingerprints = try {
            payoutFingerprints(accountId)
        } catch (e: Exception) {
            log.error("Fraud bank check: couldn't read external accounts for $accountId: ${e.message}")
            return
        }
        for (fp in fingerprints) {
            try {
                val isNew = record(user.id, BANK, fp)
                val match = banned.findByTypeAndValue(BANK, fp)
                if (match != null && match.sourceUserId != user.id && !user.suspended) {
                    suspendUser(user, "Banned payout bank account")
                    pausePayouts(accountId)
                    alert(
                        "Suspended: ${user.email} connected a banned bank account",
                        "User ${user.id} (${user.email}, \"${user.displayName}\") connected a payout account " +
                            "that belongs to banned user ${match.sourceUserId}. The account was suspended and " +
                            "its payouts paused. Review it in Stripe ($accountId) and reject it if it's the same person."
                    )
                    return
                }
                if (isNew) {
                    val limit = settings.long(RuntimeSettings.FRAUD_SHARED_BANK_ALERT)
                    val count = signals.countUsersWith(BANK, fp)
                    if (limit > 0 && count >= limit) {
                        alert(
                            "Review: one bank account on $count TapTapTips accounts",
                            "User ${user.id} (${user.email}) just connected a payout account that $count users " +
                                "now share. That can be a family or a business, or one person running several " +
                                "accounts. Nothing was blocked. Fingerprint: $fp"
                        )
                    }
                }
            } catch (e: Exception) {
                log.error("Fraud bank check failed for ${user.id}: ${e.message}")
            }
        }
    }

    private fun payoutFingerprints(accountId: String): List<String> {
        val account = Account.retrieve(accountId)
        return account.externalAccounts?.data.orEmpty().mapNotNull {
            when (it) {
                is BankAccount -> it.fingerprint
                is Card -> it.fingerprint
                else -> null
            }
        }.filter { it.isNotBlank() }.distinct()
    }

    // ── Banning ──────────────────────────────────────────────────────────────

    data class BanResult(
        val userId: UUID,
        val email: String,
        val added: List<String>,
        val alreadyBanned: List<String>,
        val payoutsPaused: Boolean?,
        val notes: List<String>
    )

    /**
     * One step: suspend the user, pause their Stripe payouts, and put their
     * email, every IP we've seen them use, [extraIps], and their payout bank
     * fingerprints (read live from Stripe) on the ban list.
     */
    fun ban(userId: UUID, reason: String, extraIps: List<String>, actor: String): BanResult {
        val user = users.findById(userId).orElseThrow { IllegalArgumentException("No user with id $userId") }
        val notes = mutableListOf<String>()

        val items = linkedSetOf<Pair<String, String>>()
        items += EMAIL to normalizeEmail(user.email)
        signals.findByUserId(userId).forEach { if (it.type in TYPES) items += it.type to it.value }
        extraIps.map { it.trim() }.filter { it.isNotEmpty() }.forEach { items += IP to it }

        var payoutsPaused: Boolean? = null
        user.stripeAccountId?.let { acct ->
            try {
                payoutFingerprints(acct).forEach {
                    items += BANK to it
                    record(userId, BANK, it)
                }
            } catch (e: Exception) {
                notes += "Couldn't read bank accounts from Stripe (${e.message}). Run the ban again later."
            }
            payoutsPaused = pausePayouts(acct)
            if (payoutsPaused == false) notes += "Couldn't pause payouts through the API — pause them in the Stripe dashboard."
        }
        if (user.stripeAccountId == null) notes += "No Stripe account, so no bank account to ban yet."

        val added = mutableListOf<String>()
        val already = mutableListOf<String>()
        for ((type, value) in items) {
            val label = "$type $value"
            if (banned.existsByTypeAndValue(type, value)) { already += label; continue }
            try {
                banned.save(BannedIdentifier(type = type, value = value, sourceUserId = userId,
                    reason = reason.take(255), createdBy = actor.take(128)))
                added += label
            } catch (e: DataIntegrityViolationException) {
                already += label
            }
        }

        suspendUser(user, reason)
        log.warn("🔨 BANNED user $userId (${user.email}) by $actor: $reason. Added ${added.size} identifiers: $added")
        return BanResult(userId, user.email, added, already, payoutsPaused, notes)
    }

    data class UnbanResult(
        val userId: UUID,
        val email: String,
        val removed: List<String>,
        val payoutsResumed: Boolean?,
        val notes: List<String>
    )

    /**
     * Undo [ban]: unsuspend the user, remove the ban-list entries that came
     * from them, and turn their Stripe payouts back to automatic (daily).
     *
     * An entry shared with another banned user (e.g. the same bank account)
     * is stored once, under whoever was banned first — so unbanning that
     * first user removes it for both. Re-ban the other user to put it back.
     */
    fun unban(userId: UUID, actor: String): UnbanResult {
        val user = users.findById(userId).orElseThrow { IllegalArgumentException("No user with id $userId") }
        val notes = mutableListOf<String>()

        val rows = banned.findBySourceUserId(userId)
        rows.forEach { banned.delete(it) }
        val removed = rows.map { "${it.type} ${it.value}" }

        if (user.suspended) {
            user.suspended = false
            user.suspensionReason = null
            user.suspendedAt = null
            user.updatedAt = Instant.now()
            users.save(user)
        } else {
            notes += "The account wasn't suspended."
        }

        var payoutsResumed: Boolean? = null
        user.stripeAccountId?.let { acct ->
            payoutsResumed = resumePayouts(acct)
            if (payoutsResumed == false) notes += "Couldn't turn Stripe payouts back on (a rejected Stripe account " +
                "can't be reopened). Check the account in the Stripe dashboard."
        }
        notes += "It can take up to a minute before they can log in again."

        log.warn("🔓 UNBANNED user $userId (${user.email}) by $actor. Removed ${removed.size} identifiers: $removed")
        return UnbanResult(userId, user.email, removed, payoutsResumed, notes)
    }

    /** Take one identifier off the ban list (e.g. an IP that turned out to be shared). */
    fun unbanIdentifier(type: String, value: String, actor: String): Boolean {
        val row = banned.findByTypeAndValue(type.uppercase(), value.trim()) ?: return false
        banned.delete(row)
        log.warn("🔓 Removed ${row.type} ${row.value} from the ban list (by $actor)")
        return true
    }

    fun list(): List<BannedIdentifier> = banned.findAllByOrderByCreatedAtDesc()

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Remembers that [userId] used [value]. Returns true the first time. */
    private fun record(userId: UUID, type: String, value: String): Boolean {
        if (value.isBlank()) return false
        val v = value.take(255)
        val existing = signals.findByUserIdAndTypeAndValue(userId, type, v)
        if (existing != null) {
            existing.lastSeen = Instant.now()
            signals.save(existing)
            return false
        }
        return try {
            signals.save(UserSignal(userId = userId, type = type, value = v))
            true
        } catch (e: DataIntegrityViolationException) {
            false   // saved by a parallel request
        }
    }

    private fun suspendUser(user: AppUser, internalReason: String) {
        if (user.suspended) return
        // The app shows suspensionReason to the user, so keep it neutral.
        // The real reason is in the log and on the ban list.
        user.suspended = true
        user.suspensionReason = PUBLIC_SUSPENSION_REASON
        user.suspendedAt = Instant.now()
        user.updatedAt = Instant.now()
        users.save(user)
        log.warn("🚫 Suspended user ${user.id} (${user.email}): $internalReason")
    }

    /** Switches payouts to manual so nothing is sent out automatically. */
    private fun pausePayouts(accountId: String): Boolean = try {
        Account.retrieve(accountId).update(
            AccountUpdateParams.builder()
                .setSettings(
                    AccountUpdateParams.Settings.builder()
                        .setPayouts(
                            AccountUpdateParams.Settings.Payouts.builder()
                                .setSchedule(
                                    AccountUpdateParams.Settings.Payouts.Schedule.builder()
                                        .setInterval(AccountUpdateParams.Settings.Payouts.Schedule.Interval.MANUAL)
                                        .build()
                                )
                                .build()
                        )
                        .build()
                )
                .build()
        )
        log.warn("⏸️ Payouts set to manual for $accountId")
        true
    } catch (e: Exception) {
        log.error("Couldn't pause payouts for $accountId: ${e.message}")
        false
    }

    /** Turns automatic (daily) payouts back on. */
    private fun resumePayouts(accountId: String): Boolean = try {
        Account.retrieve(accountId).update(
            AccountUpdateParams.builder()
                .setSettings(
                    AccountUpdateParams.Settings.builder()
                        .setPayouts(
                            AccountUpdateParams.Settings.Payouts.builder()
                                .setSchedule(
                                    AccountUpdateParams.Settings.Payouts.Schedule.builder()
                                        .setInterval(AccountUpdateParams.Settings.Payouts.Schedule.Interval.DAILY)
                                        .build()
                                )
                                .build()
                        )
                        .build()
                )
                .build()
        )
        log.warn("▶️ Payouts set back to daily for $accountId")
        true
    } catch (e: Exception) {
        log.error("Couldn't resume payouts for $accountId: ${e.message}")
        false
    }

    private fun alert(subject: String, body: String) {
        log.error("🚨 FRAUD MATCH — $subject\n$body")
        email.sendAdminAlertAsync("[TapTapTips fraud] $subject", body)
    }
}
