package com.taptaptips.server.service

import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.Notification
import com.stripe.exception.StripeException
import com.stripe.model.PaymentMethod
import com.stripe.model.Transfer
import com.stripe.net.RequestOptions
import com.stripe.param.PaymentMethodListParams
import com.stripe.param.TransferCreateParams
import com.stripe.param.TransferListParams
import com.taptaptips.server.domain.AppUser
import com.taptaptips.server.domain.Referral
import com.taptaptips.server.domain.ReferralCode
import com.taptaptips.server.domain.ReferralStatus
import com.taptaptips.server.repo.AppUserRepository
import com.taptaptips.server.repo.DeviceRepository
import com.taptaptips.server.repo.FcmTokenRepository
import com.taptaptips.server.repo.ReferralCodeRepository
import com.taptaptips.server.repo.ReferralRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Referral program: the referrer earns cash when someone they invited creates
 * an account AND adds a real payment card.
 *
 * Abuse protection:
 *  - A code can only be applied to a brand-new account (within [applyWindow]).
 *  - No self-referral: the new account can't share a device key or a card with
 *    the referrer.
 *  - One reward per physical card (Stripe card fingerprint), ever.
 *  - Monthly cap per referrer ([monthlyCap]); extra referrals are recorded as CAPPED.
 *  - Payout waits [payoutDelay] after qualifying, so mass sign-ups can be spotted
 *    and rejected (set status = 'REJECTED' in the database) before money moves.
 *  - Suspended accounts never earn or trigger rewards.
 *
 * Money safety mirrors held tips: atomic status claims, a transfer-group check
 * before paying so a referral is never paid twice, and recovery of rows stuck
 * mid-payment.
 *
 * Turning the program off (referral.enabled=false) only stops NEW codes from
 * being applied and hides it in the apps. Referrals made while it was on still
 * qualify (within [qualifyWindow]) and still get paid — so ending a promotion
 * never breaks a promise. To freeze ALL payouts (e.g. you spot abuse), set
 * referral.payoutsPaused=true.
 *
 * Config (all optional):
 *   referral.enabled              false   (switch on from Render when ready)
 *   referral.payoutsPaused        false
 *   referral.qualifyWindowDays    30
 *   referral.rewardCents          500
 *   referral.monthlyCap           50
 *   referral.payoutDelayHours     24
 *   referral.applyWindowHours     24
 *   referral.shareUrl             https://taptaptips.com
 */
@Service
class ReferralService(
    private val codes: ReferralCodeRepository,
    private val referrals: ReferralRepository,
    private val users: AppUserRepository,
    private val devices: DeviceRepository,
    private val fcmTokens: FcmTokenRepository,
    private val stripe: StripePaymentService,
    txManager: PlatformTransactionManager,

    @Value("\${referral.enabled:false}") private val enabled: Boolean,
    @Value("\${referral.payoutsPaused:false}") private val payoutsPaused: Boolean,
    @Value("\${referral.qualifyWindowDays:30}") private val qualifyWindowDays: Long,
    @Value("\${referral.rewardCents:500}") private val rewardCents: Long,
    @Value("\${referral.monthlyCap:50}") private val monthlyCap: Long,
    @Value("\${referral.payoutDelayHours:24}") private val payoutDelayHours: Long,
    @Value("\${referral.applyWindowHours:24}") private val applyWindowHours: Long,
    @Value("\${referral.shareUrl:https://taptaptips.com}") private val shareUrl: String
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val rng = SecureRandom()
    private val tx = TransactionTemplate(txManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    private val payoutDelay get() = Duration.ofHours(payoutDelayHours)
    private val applyWindow get() = Duration.ofHours(applyWindowHours)

    companion object {
        /** No 0/O or 1/I/L — easy to read aloud and type. */
        private const val CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        private const val CODE_LENGTH = 6
        private val CODE_REGEX = Regex("^[A-Z0-9]{4,12}$")
        private val REWARDED = listOf(ReferralStatus.QUALIFIED, ReferralStatus.PAYING, ReferralStatus.PAID)
        private val STUCK_AFTER: Duration = Duration.ofMinutes(15)
        private const val MAX_PAY_ATTEMPTS = 10
    }

    // ════════════════════════════════════════════════════════════════════════
    // Codes and summary
    // ════════════════════════════════════════════════════════════════════════

    fun codeFor(userId: UUID): String {
        codes.findById(userId).orElse(null)?.let { return it.code }
        var code: String
        do { code = generateCode() } while (codes.existsByCode(code))
        return try {
            codes.save(ReferralCode(userId = userId, code = code)).code
        } catch (e: Exception) {
            // Two requests raced to create it — return whichever won.
            codes.findById(userId).orElseThrow { e }.code
        }
    }

    /** What the apps need to decide whether to show the program at all. */
    data class PublicConfig(val enabled: Boolean, val rewardCents: Long, val monthlyCap: Long)

    fun publicConfig() = PublicConfig(enabled, rewardCents, monthlyCap)

    data class Summary(
        val enabled: Boolean,
        val code: String,
        /** What the QR code encodes: the landing page for this code. */
        val shareLink: String,
        val shareMessage: String,
        val rewardCents: Long,
        val pendingCount: Long,      // signed up, no card yet
        val waitingCount: Long,      // qualified, payout coming
        val paidCount: Long,
        val earnedCents: Long,
        val remainingThisMonth: Long,
        val payoutAccountReady: Boolean
    )

    fun summaryFor(userId: UUID): Summary {
        val user = users.findById(userId).orElseThrow()
        val code = codeFor(userId)
        val usedThisMonth = referrals.countRewardsSince(userId, REWARDED, Instant.now().minus(Duration.ofDays(30)))
        return Summary(
            enabled = enabled,
            code = code,
            shareLink = shareLinkFor(code),
            shareMessage = "Tip people nearby with a tap — no cash needed. " +
                    "Join me on TapTapTips with my code $code: ${shareLinkFor(code)}",
            rewardCents = rewardCents,
            pendingCount = referrals.countByReferrerIdAndStatus(userId, ReferralStatus.PENDING),
            waitingCount = referrals.countByReferrerIdAndStatus(userId, ReferralStatus.QUALIFIED) +
                    referrals.countByReferrerIdAndStatus(userId, ReferralStatus.PAYING),
            paidCount = referrals.countByReferrerIdAndStatus(userId, ReferralStatus.PAID),
            earnedCents = referrals.sumRewardCents(userId, ReferralStatus.PAID),
            remainingThisMonth = (monthlyCap - usedThisMonth).coerceAtLeast(0),
            payoutAccountReady = user.stripeOnboarded && user.stripeAccountId != null
        )
    }

    fun shareLinkFor(code: String): String = "${shareUrl.trimEnd('/')}/r/$code"

    /** True if this code belongs to an active (not suspended) user. */
    fun isValidCode(code: String): Boolean {
        val owner = codes.findByCode(code) ?: return false
        return users.findById(owner.userId).map { !it.suspended }.orElse(false)
    }

    // ════════════════════════════════════════════════════════════════════════
    // Applying a code (at sign-up, or shortly after)
    // ════════════════════════════════════════════════════════════════════════

    /** Returns null on success, or a user-facing reason it was refused. */
    fun applyCode(newUser: AppUser, rawCode: String?): String? {
        if (!enabled) return "The referral program isn't running right now."
        val code = rawCode?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return "Please enter a referral code."
        if (!CODE_REGEX.matches(code)) return "That referral code isn't valid."

        if (referrals.findByReferredUserId(newUser.id) != null) return "You've already used a referral code."
        if (newUser.createdAt.isBefore(Instant.now().minus(applyWindow))) {
            return "Referral codes can only be added when you first sign up."
        }

        val owner = codes.findByCode(code) ?: return "That referral code isn't valid."
        if (owner.userId == newUser.id) return "You can't use your own referral code."
        val referrer = users.findById(owner.userId).orElse(null) ?: return "That referral code isn't valid."
        if (referrer.suspended) return "That referral code isn't valid."

        return try {
            referrals.save(
                Referral(referrerId = referrer.id, referredUserId = newUser.id, code = code, rewardCents = rewardCents)
            )
            log.info("🤝 Referral recorded: ${referrer.id} → ${newUser.id} ($code)")
            null
        } catch (e: Exception) {
            // unique(referred_user_id) — a parallel request already applied one
            "You've already used a referral code."
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Qualifying (new user added a card)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Called after a user successfully saves a card. Cheap no-op for users who
     * weren't referred or already qualified. Never throws — a referral problem
     * must not break adding a card.
     */
    fun onCardAdded(user: AppUser, paymentMethod: PaymentMethod) {
        try {
            val ref = referrals.findByReferredUserId(user.id) ?: return
            if (ref.status != ReferralStatus.PENDING) return
            qualify(ref, user, paymentMethod.card?.fingerprint)
        } catch (e: Exception) {
            log.error("Referral check after card add failed for ${user.id}", e)
        }
    }

    private fun qualify(ref: Referral, newUser: AppUser, fingerprint: String?) {
        val referrer = users.findById(ref.referrerId).orElse(null)

        val rejection: String? = when {
            ref.createdAt.isBefore(Instant.now().minus(Duration.ofDays(qualifyWindowDays))) -> "QUALIFY_WINDOW_EXPIRED"
            referrer == null -> "REFERRER_DELETED"
            referrer.suspended || newUser.suspended -> "ACCOUNT_SUSPENDED"
            fingerprint == null -> "NO_CARD_FINGERPRINT"
            referrals.cardAlreadyRewarded(fingerprint, REWARDED, ref.id) -> "CARD_ALREADY_REWARDED"
            referrerHasCard(referrer, fingerprint) -> "SELF_REFERRAL_CARD"
            sharesDevice(referrer.id, newUser.id) -> "SELF_REFERRAL_DEVICE"
            else -> null
        }

        tx.execute {
            val r = referrals.findById(ref.id).orElseThrow()
            if (r.status != ReferralStatus.PENDING) return@execute
            r.cardFingerprint = fingerprint
            r.updatedAt = Instant.now()
            when {
                rejection != null -> {
                    r.status = ReferralStatus.REJECTED
                    r.rejectReason = rejection
                    log.warn("🚫 Referral ${r.id} rejected: $rejection")
                }
                referrals.countRewardsSince(r.referrerId, REWARDED, Instant.now().minus(Duration.ofDays(30))) >= monthlyCap -> {
                    r.status = ReferralStatus.CAPPED
                    r.rejectReason = "MONTHLY_CAP"
                    log.info("🧢 Referral ${r.id} over monthly cap for ${r.referrerId}")
                }
                else -> {
                    r.status = ReferralStatus.QUALIFIED
                    r.qualifiedAt = Instant.now()
                    log.info("✅ Referral ${r.id} qualified — referrer ${r.referrerId} earns ${r.rewardCents}¢")
                }
            }
            referrals.save(r)
        }
    }

    private fun referrerHasCard(referrer: AppUser, fingerprint: String): Boolean {
        val customerId = referrer.stripeCustomerId ?: return false
        return try {
            PaymentMethod.list(
                PaymentMethodListParams.builder()
                    .setCustomer(customerId)
                    .setType(PaymentMethodListParams.Type.CARD)
                    .setLimit(100L)
                    .build()
            ).data.any { it.card?.fingerprint == fingerprint }
        } catch (e: StripeException) {
            log.warn("Could not list referrer cards for ${referrer.id}: ${e.message}")
            false
        }
    }

    /** Same app install (device signing key) on both accounts = same phone. */
    private fun sharesDevice(referrerId: UUID, newUserId: UUID): Boolean {
        val referrerKeys = devices.findAllByUser_Id(referrerId).map { it.publicKey.toList() }.toSet()
        if (referrerKeys.isEmpty()) return false
        return devices.findAllByUser_Id(newUserId).any { it.publicKey.toList() in referrerKeys }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Paying
    // ════════════════════════════════════════════════════════════════════════

    @Scheduled(fixedDelayString = "\${referral.sweepIntervalMs:900000}", initialDelay = 120_000)
    fun sweep() {
        // Owed rewards keep paying even after a promotion ends.
        if (payoutsPaused) return
        try {
            recoverStuck()
            referrals.findPayableIds(ReferralStatus.QUALIFIED, Instant.now().minus(payoutDelay))
                .forEach { payOne(it) }
        } catch (e: Exception) {
            log.error("❌ Referral sweep failed", e)
        }
    }

    private fun payOne(id: UUID) {
        val ref = referrals.findById(id).orElse(null) ?: return
        val referrer = users.findById(ref.referrerId).orElse(null) ?: return
        val accountId = referrer.stripeAccountId
        if (referrer.suspended) return
        // Referrer hasn't set up payouts yet — keep it owed, try again next sweep.
        if (accountId == null || !stripe.isAccountReadyForTransfers(accountId, referrer.id)) return

        val claimed = tx.execute {
            referrals.transition(id, ReferralStatus.QUALIFIED, ReferralStatus.PAYING, Instant.now()) == 1
        } ?: false
        if (!claimed) return

        val group = "referral_$id"
        try {
            val transfer = findTransfer(group) ?: Transfer.create(
                TransferCreateParams.builder()
                    .setAmount(ref.rewardCents)
                    .setCurrency("usd")
                    .setDestination(accountId)
                    .setTransferGroup(group)
                    .setDescription("TapTapTips referral reward")
                    .putMetadata("referral_id", id.toString())
                    .putMetadata("referrer_id", ref.referrerId.toString())
                    .putMetadata("referred_user_id", ref.referredUserId.toString())
                    .build(),
                // Hourly key: a failure isn't replayed for 24h (see held tips).
                // The transfer-group check above prevents double payment.
                RequestOptions.builder()
                    .setIdempotencyKey("referral-transfer-$id-${Instant.now().epochSecond / 3600}")
                    .build()
            )
            tx.execute {
                val r = referrals.findById(id).orElseThrow()
                r.status = ReferralStatus.PAID
                r.transferId = transfer.id
                r.paidAt = Instant.now()
                r.lastError = null
                r.updatedAt = Instant.now()
                referrals.save(r)
            }
            log.info("💸 Paid referral $id: ${ref.rewardCents}¢ to ${ref.referrerId} (${transfer.id})")
            notify(ref.referrerId, ref.rewardCents)
        } catch (e: Exception) {
            log.warn("⚠️ Referral payout $id failed: ${e.message}")
            tx.execute {
                val r = referrals.findById(id).orElseThrow()
                r.payAttempts += 1
                r.lastError = e.message?.take(1000)
                r.status = if (r.payAttempts >= MAX_PAY_ATTEMPTS) ReferralStatus.REJECTED else ReferralStatus.QUALIFIED
                if (r.status == ReferralStatus.REJECTED) r.rejectReason = "PAYOUT_FAILED_MAX_ATTEMPTS"
                r.updatedAt = Instant.now()
                referrals.save(r)
            }
        }
    }

    /** A crash mid-payment leaves PAYING rows; check Stripe instead of guessing. */
    private fun recoverStuck() {
        referrals.findStuckIds(ReferralStatus.PAYING, Instant.now().minus(STUCK_AFTER)).forEach { id ->
            val existing = try { findTransfer("referral_$id") } catch (e: Exception) { return@forEach }
            tx.execute {
                val r = referrals.findById(id).orElseThrow()
                if (existing != null) {
                    r.status = ReferralStatus.PAID
                    r.transferId = existing.id
                    r.paidAt = r.paidAt ?: Instant.now()
                } else {
                    r.status = ReferralStatus.QUALIFIED
                }
                r.updatedAt = Instant.now()
                referrals.save(r)
            }
        }
    }

    private fun findTransfer(group: String): Transfer? =
        Transfer.list(TransferListParams.builder().setTransferGroup(group).setLimit(1L).build())
            .data.firstOrNull()

    private fun notify(userId: UUID, cents: Long) {
        try {
            val body = "You earned $%.2f — a friend you invited joined TapTapTips. It's on the way to your bank."
                .format(cents / 100.0)
            fcmTokens.findByUser_IdAndIsActiveTrue(userId).forEach { t ->
                try {
                    FirebaseMessaging.getInstance().send(
                        Message.builder()
                            .setToken(t.token)
                            .setNotification(Notification.builder().setTitle("🎉 Referral reward").setBody(body).build())
                            .putData("type", "referral_reward")
                            .build()
                    )
                } catch (e: Exception) {
                    log.debug("Referral push failed for one device: ${e.message}")
                }
            }
        } catch (e: Exception) {
            log.warn("Referral notification failed for $userId: ${e.message}")
        }
    }

    private fun generateCode(): String =
        (1..CODE_LENGTH).map { CODE_ALPHABET[rng.nextInt(CODE_ALPHABET.length)] }.joinToString("")
}
