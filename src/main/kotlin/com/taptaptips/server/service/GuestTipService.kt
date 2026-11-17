package com.taptaptips.server.service

import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.Notification
import com.stripe.model.PaymentIntent
import com.stripe.param.PaymentIntentCreateParams
import com.taptaptips.server.domain.AppUser
import com.taptaptips.server.domain.GuestTip
import com.taptaptips.server.domain.Tip
import com.taptaptips.server.domain.TipStatus
import com.taptaptips.server.repo.AppUserRepository
import com.taptaptips.server.repo.FcmTokenRepository
import com.taptaptips.server.repo.GuestTipRepository
import com.taptaptips.server.repo.ReferralCodeRepository
import com.taptaptips.server.repo.TipRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Tips from people WITHOUT the app ("guest tips").
 *
 * A receiver's tip link is tip.taptaptips.com/t/CODE — CODE is the same
 * permanent random code as their referral link (/r/CODE), so each person has
 * one code everywhere. Written on NFC tags / QR codes; never changes.
 *
 * Flow:
 *  1. Tipper opens /t/CODE → picks an amount → Apple Pay / Google Pay / card.
 *  2. [createIntent] makes a PaymentIntent charged on the PLATFORM (platform
 *     MCC, not the receiver's), with transfer_data → receiver, fee kept as
 *     application_fee — the same money flow as app tips.
 *  3. When it succeeds ([finalize], called by the page and by a 1-minute
 *     sweep as backup) a normal Tip row is created (sender = null) and the
 *     receiver gets a push.
 *
 * Abuse limits (all changeable on /admin):
 *  - amount min/max, and a lower max for receivers in their new account period
 *  - requests per IP (trusted IP, see ClientIp) and per receiver
 *  - a receiver's link pauses after too many FAILED tips in an hour
 *    (many different failing cards = card testing / stolen-card cash-out)
 *  - 3D Secure above a set amount, and on every tip to a new account
 *  - a declined PaymentIntent is canceled (see [onPaymentFailed]) so its
 *    client secret can't be reused to try card after card past our limits
 * Turn off entirely with TIPS_GUEST_ENABLED=false.
 */
@Service
class GuestTipService(
    private val codes: ReferralCodeRepository,
    private val referrals: ReferralService,
    private val users: AppUserRepository,
    private val tips: TipRepository,
    private val guestTips: GuestTipRepository,
    private val fcmTokens: FcmTokenRepository,
    private val stripe: StripePaymentService,
    private val settings: RuntimeSettings,
    @Value("\${tips.guest.baseUrl:https://tip.taptaptips.com}") private val baseUrl: String,
    @Value("\${tips.guest.timezone:America/Los_Angeles}") private val timezone: String
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // Changeable from the admin page (/admin) without a redeploy — see RuntimeSettings.
    val enabled get() = settings.bool(RuntimeSettings.GUEST_ENABLED)
    val minCents get() = settings.long(RuntimeSettings.GUEST_MIN_CENTS)
    val maxCents get() = settings.long(RuntimeSettings.GUEST_MAX_CENTS)
    private val perIpPer10Min get() = settings.int(RuntimeSettings.GUEST_PER_IP)
    private val perReceiverPerHour get() = settings.int(RuntimeSettings.GUEST_PER_RECEIVER)
    private val maxFailsPerReceiverPerHour get() = settings.int(RuntimeSettings.GUEST_MAX_FAILS_PER_RECEIVER)
    private val threeDsAboveCents get() = settings.long(RuntimeSettings.GUEST_3DS_ABOVE_CENTS)
    private val newReceiverDays get() = settings.long(RuntimeSettings.GUEST_NEW_RECEIVER_DAYS)
    private val newReceiverMaxCents get() = settings.long(RuntimeSettings.GUEST_NEW_RECEIVER_MAX_CENTS)
    private val codeRegex = Regex("^[A-Z0-9]{4,12}$")

    data class Receiver(val user: AppUser, val code: String) {
        /** Name shown to tippers: first name + last initial ("Vernon A."). */
        val publicName: String get() {
            val parts = user.displayName.trim().split(Regex("\\s+"))
            return if (parts.size >= 2) "${parts[0]} ${parts.last().first().uppercaseChar()}." else parts[0]
        }
    }

    class GuestTipException(message: String) : RuntimeException(message)

    // ── Lookups ──────────────────────────────────────────────────────────────

    fun linkFor(code: String): String = "${baseUrl.trimEnd('/')}/t/$code"

    /** The receiver behind a code, or null if the code is unknown. */
    fun findReceiver(rawCode: String): Receiver? {
        val code = rawCode.trim().uppercase()
        if (!codeRegex.matches(code)) return null
        val userId = codes.findByCode(code)?.userId ?: return null
        val user = users.findById(userId).orElse(null) ?: return null
        return Receiver(user, code)
    }

    /** Why this receiver can't take tips right now, or null if they can. */
    fun unavailableReason(r: Receiver): String? = when {
        !enabled -> "Tipping by link isn't available right now."
        r.user.suspended -> "This person can't receive tips right now."
        // Same rule as the app's QR icon: a Stripe account that finished onboarding.
        // (wantsToReceiveTips is only set at sign-up, so it's not checked here.)
        r.user.stripeAccountId.isNullOrBlank() || !r.user.stripeOnboarded ->
            "${r.publicName} hasn't finished setting up tips yet."
        else -> null
    }

    data class MyLink(val ready: Boolean, val code: String, val url: String, val message: String?)

    /** For the receiver's "My tip link" screen in the app. */
    fun myLink(userId: UUID): MyLink {
        val user = users.findById(userId).orElseThrow()
        val code = referrals.codeFor(userId)
        val reason = unavailableReason(Receiver(user, code))
        return MyLink(ready = reason == null, code = code, url = linkFor(code),
            message = reason?.let { "Finish setting up direct deposit to start receiving tips by link." })
    }

    // ── Payment ──────────────────────────────────────────────────────────────

    /** True while [user] is in the new account period (stricter limits for guest tips). */
    fun isNewReceiver(user: AppUser): Boolean {
        val days = newReceiverDays
        return days > 0 && user.createdAt.isAfter(Instant.now().minus(Duration.ofDays(days)))
    }

    /** Largest tip this receiver can get by link right now. */
    fun maxCentsFor(user: AppUser): Long =
        if (isNewReceiver(user)) minOf(maxCents, newReceiverMaxCents) else maxCents

    /** Creates the PaymentIntent for the web page. Returns its client secret. */
    fun createIntent(rawCode: String, amountCents: Long, clientIp: String, userAgent: String = ""): String {
        val r = findReceiver(rawCode) ?: throw GuestTipException("This tip link isn't valid.")
        unavailableReason(r)?.let { throw GuestTipException(it) }
        val newReceiver = isNewReceiver(r.user)
        val max = maxCentsFor(r.user)
        if (amountCents < minCents) throw GuestTipException("The minimum tip is ${money(minCents)}.")
        if (amountCents > max) throw GuestTipException("The maximum tip is ${money(max)}.")

        val recentFails = guestTips.countFailedSince(r.user.id, Instant.now().minus(Duration.ofHours(1)))
        if (recentFails >= maxFailsPerReceiverPerHour) {
            log.warn("🚨 Guest tip refused: receiver ${r.user.id} has $recentFails failed tips in the last hour " +
                "(ip=$clientIp)")
            throw GuestTipException("${r.publicName} can't receive tips by link right now. Please try again later.")
        }
        if (!allow("ip:$clientIp", perIpPer10Min, Duration.ofMinutes(10)) ||
            !allow("rcv:${r.user.id}", perReceiverPerHour, Duration.ofHours(1))) {
            log.warn("⚠️ Guest tip rate limit: receiver=${r.user.id} ip=$clientIp")
            throw GuestTipException("Too many attempts. Please wait a few minutes and try again.")
        }

        val fees = stripe.calculateFeeBreakdown(BigDecimal(amountCents).movePointLeft(2))
        if (fees.receiverGetsCents <= 0) throw GuestTipException("That amount is too small to send.")

        // Ask the card's bank to verify the payer. Stolen cards usually fail this,
        // and when it passes, fraud chargeback liability generally moves to the bank.
        val ask3ds = newReceiver || amountCents > threeDsAboveCents

        val params = PaymentIntentCreateParams.builder()
            .setAmount(amountCents)
            .setCurrency("usd")
            .addPaymentMethodType("card")           // includes Apple Pay & Google Pay
            .setApplicationFeeAmount(fees.totalFeeCents)
            .setTransferData(
                PaymentIntentCreateParams.TransferData.builder()
                    .setDestination(r.user.stripeAccountId!!).build()
            )
            // No on_behalf_of: charged on the platform (purchase MCC),
            // same as app tips — avoids receivers' MCC 4829.
            .setStatementDescriptorSuffix("TIP")
            .setDescription("TapTapTips guest tip to ${r.publicName}")
            .putMetadata("charge_type", "guest_tip")
            .putMetadata("receiver_id", r.user.id.toString())
            .putMetadata("tip_code", r.code)
            .putMetadata("receiver_net_cents", fees.receiverGetsCents.toString())
            // For investigating fraud later (Stripe keeps these; we don't store them).
            .putMetadata("guest_ip", clientIp.take(45))
            .putMetadata("guest_ua", userAgent.take(450))
            .putMetadata("receiver_new_account", newReceiver.toString())

        if (ask3ds) {
            params.setPaymentMethodOptions(
                PaymentIntentCreateParams.PaymentMethodOptions.builder()
                    .setCard(
                        PaymentIntentCreateParams.PaymentMethodOptions.Card.builder()
                            .setRequestThreeDSecure(
                                PaymentIntentCreateParams.PaymentMethodOptions.Card.RequestThreeDSecure.ANY
                            )
                            .build()
                    )
                    .build()
            )
        }

        val pi = PaymentIntent.create(params.build())
        guestTips.save(
            GuestTip(
                paymentIntentId = pi.id,
                receiverId = r.user.id,
                amountCents = amountCents,
                netCents = fees.receiverGetsCents
            )
        )
        log.info("🌐 Guest tip intent ${pi.id}: ${money(amountCents)} → ${r.user.id} " +
            "ip=$clientIp 3ds=$ask3ds newReceiver=$newReceiver ua=\"${userAgent.take(120)}\"")
        return pi.clientSecret
    }

    /**
     * Called from the Stripe webhook when a guest-tip payment fails (declined,
     * or blocked by Radar). Cancels the PaymentIntent so the same client secret
     * can't be used to try more cards straight against Stripe — every new card
     * then needs a new, rate-limited [createIntent]. Records the failure so
     * [maxFailsPerReceiverPerHour] can pause the receiver's link.
     */
    fun onPaymentFailed(paymentIntentId: String, reason: String?) {
        val g = guestTips.findByPaymentIntentId(paymentIntentId) ?: return
        if (g.status != "PENDING") return

        // Cancel first. If it can't be canceled (it went on to succeed or is
        // processing), leave it PENDING so finalize() records it normally.
        val canceled = try {
            PaymentIntent.retrieve(paymentIntentId).cancel()
            true
        } catch (e: Exception) {
            log.warn("Guest tip $paymentIntentId failed but couldn't be canceled: ${e.message}")
            false
        }
        if (!canceled) return

        guestTips.claim(paymentIntentId, "FAILED", Instant.now())
        val fails = guestTips.countFailedSince(g.receiverId, Instant.now().minus(Duration.ofHours(1)))
        log.warn("❌ Guest tip $paymentIntentId failed (${reason ?: "no reason"}) → receiver ${g.receiverId}, " +
            "$fails failed in the last hour")
        if (fails >= maxFailsPerReceiverPerHour) {
            log.error("🚨 POSSIBLE CARD TESTING: receiver ${g.receiverId} has $fails failed guest tips in the " +
                "last hour. Their tip link is paused. Review them in Stripe (Connected accounts) and " +
                "consider pausing payouts.")
        }
    }

    /**
     * Checks the PaymentIntent and, if paid, records the tip and notifies the
     * receiver (exactly once). Safe to call repeatedly. Returns the status.
     */
    fun finalize(paymentIntentId: String): String {
        val g = guestTips.findByPaymentIntentId(paymentIntentId) ?: return "unknown"
        if (g.status != "PENDING") return g.status.lowercase()

        val pi = try { PaymentIntent.retrieve(paymentIntentId) } catch (e: Exception) {
            log.warn("Guest tip $paymentIntentId: could not retrieve: ${e.message}")
            return "pending"
        }
        return when (pi.status) {
            "succeeded" -> {
                if (guestTips.claim(paymentIntentId, "SUCCEEDED", Instant.now()) == 1) {
                    recordAndNotify(g, pi)
                }
                "succeeded"
            }
            "canceled" -> {
                guestTips.claim(paymentIntentId, "ABANDONED", Instant.now()); "abandoned"
            }
            else -> {
                if (g.createdAt.isBefore(Instant.now().minus(Duration.ofHours(24)))) {
                    runCatching { pi.cancel() }
                    guestTips.claim(paymentIntentId, "ABANDONED", Instant.now()); "abandoned"
                } else "pending"
            }
        }
    }

    /** For the tip page: status + amount, only if [paymentIntentId] belongs to [rawCode]. */
    fun completeForPage(rawCode: String, paymentIntentId: String): Pair<String, Long?> {
        val r = findReceiver(rawCode) ?: return "unknown" to null
        val g = guestTips.findByPaymentIntentId(paymentIntentId) ?: return "unknown" to null
        if (g.receiverId != r.user.id) return "unknown" to null
        return finalize(paymentIntentId) to g.amountCents
    }

    /** Backup for tippers who close the page before it reports back. */
    @Scheduled(fixedDelayString = "\${tips.guest.sweepMs:60000}", initialDelay = 60_000)
    fun sweep() {
        try {
            guestTips.findPendingCreatedBefore(Instant.now().minusSeconds(30))
                .take(100)
                .forEach { finalize(it) }
        } catch (e: Exception) {
            log.error("Guest tip sweep failed", e)
        }
    }

    private fun recordAndNotify(g: GuestTip, pi: PaymentIntent) {
        try {
            val receiver = users.findById(g.receiverId).orElse(null)
            val fees = stripe.calculateFeeBreakdown(BigDecimal(g.amountCents).movePointLeft(2))
            val zone = runCatching { ZoneId.of(timezone) }.getOrDefault(ZoneId.of("America/Los_Angeles"))
            val tip = tips.save(
                Tip(
                    sender = null,
                    receiver = receiver,
                    amount = fees.senderPays,
                    netAmount = fees.receiverGets,
                    totalFees = fees.totalFee,
                    platformFee = fees.platformFee,
                    createdAt = Instant.now(),
                    createdAtLocal = LocalDate.now(zone),
                    timezone = zone.id,
                    nonce = "guest-${pi.id}",
                    timestamp = System.currentTimeMillis(),
                    verified = false,
                    status = TipStatus.SUCCEEDED,
                    paymentIntentId = pi.id,
                    chargeId = pi.latestCharge,
                    updatedAt = Instant.now()
                )
            )
            guestTips.linkTip(pi.id, tip.id)
            log.info("✅ Guest tip ${pi.id} recorded as tip ${tip.id}")
            notifyReceiver(g.receiverId, g.netCents, tip.id)
        } catch (e: Exception) {
            // Money already moved (transfer_data); only our record failed. Logged loudly.
            log.error("❌ Guest tip ${pi.id} paid but recording failed — check manually", e)
        }
    }

    private fun notifyReceiver(userId: UUID, netCents: Long, tipId: UUID) {
        val body = "You got a ${money(netCents)} tip from a guest via your tip link."
        try {
            fcmTokens.findByUser_IdAndIsActiveTrue(userId).forEach { t ->
                try {
                    FirebaseMessaging.getInstance().send(
                        Message.builder()
                            .setToken(t.token)
                            .setNotification(Notification.builder().setTitle("💸 New tip!").setBody(body).build())
                            .putData("type", "guest_tip")
                            .putData("tipId", tipId.toString())
                            .putData("amount", BigDecimal(netCents).movePointLeft(2).setScale(2, RoundingMode.HALF_UP).toPlainString())
                            .build()
                    )
                } catch (e: Exception) {
                    log.debug("Guest tip push failed for one device: ${e.message}")
                }
            }
        } catch (e: Exception) {
            log.warn("Guest tip notification failed for $userId: ${e.message}")
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private val hits = ConcurrentHashMap<String, ArrayDeque<Long>>()

    private fun allow(key: String, limit: Int, window: Duration): Boolean {
        val now = System.currentTimeMillis()
        val q = hits.computeIfAbsent(key) { ArrayDeque() }
        synchronized(q) {
            while (q.isNotEmpty() && q.peekFirst() < now - window.toMillis()) q.pollFirst()
            if (q.size >= limit) return false
            q.addLast(now)
        }
        // Memory bound. Drop only stale keys — clearing everything would let a
        // flood of requests wipe the per-receiver counters too.
        if (hits.size > 50_000) {
            val cutoff = now - Duration.ofHours(1).toMillis()
            hits.entries.removeIf { (_, dq) -> synchronized(dq) { dq.isEmpty() || dq.peekLast() < cutoff } }
            if (hits.size > 50_000) hits.keys.removeIf { it.startsWith("ip:") }
        }
        return true
    }

    fun money(cents: Long): String =
        if (cents % 100 == 0L) "$${cents / 100}" else "$%.2f".format(cents / 100.0)
}
