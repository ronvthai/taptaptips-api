package com.taptaptips.server.service

import com.taptaptips.server.domain.AppUser
import com.taptaptips.server.domain.HeldTip
import com.taptaptips.server.domain.HeldTipStatus
import com.taptaptips.server.domain.Tip
import com.taptaptips.server.domain.TipStatus
import com.taptaptips.server.repo.AppUserRepository
import com.taptaptips.server.repo.DeviceRepository
import com.taptaptips.server.repo.HeldTipRepository
import com.taptaptips.server.repo.TipRepository
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Everything about tips whose receiver hasn't linked a bank yet.
 *
 * Money safety rules this class enforces:
 *  1. A held tip can only leave HELD through an atomic conditional UPDATE, so
 *     release and refund can never both happen, and no row is paid twice.
 *  2. Every Stripe money call carries an idempotency key derived from the
 *     held-tip id, so a retried call returns the original object.
 *  3. Before paying out, the row's HMAC is re-verified and cross-checked with
 *     the tip row; any mismatch BLOCKS the payout for manual review.
 *  4. Funds only go to a connected account that is transfer-capable AND whose
 *     Stripe metadata says it belongs to the receiver.
 *  5. Per-receiver and per-sender caps limit how much can sit in holding.
 *  6. Unclaimed tips are refunded to the sender after `tips.hold.expiryDays`.
 */
@Service
class HeldTipService(
    private val heldTips: HeldTipRepository,
    private val tips: TipRepository,
    private val users: AppUserRepository,
    private val devices: DeviceRepository,
    private val stripe: StripePaymentService,
    private val notifications: NotificationService,
    txManager: PlatformTransactionManager,

    @Value("\${tips.hold.hmacSecret:}") private val hmacSecret: String,
    @Value("\${tips.hold.expiryDays:30}") private val expiryDays: Long,
    @Value("\${tips.hold.maxPendingPerReceiverCents:50000}") private val maxPendingPerReceiverCents: Long,
    @Value("\${tips.hold.maxPendingCountPerReceiver:50}") private val maxPendingCountPerReceiver: Long,
    @Value("\${tips.hold.maxPerSenderPerDayCents:20000}") private val maxPerSenderPerDayCents: Long,
    @Value("\${tips.hold.maxReleaseAttempts:10}") private val maxReleaseAttempts: Int
) {
    private val log = LoggerFactory.getLogger(javaClass)
    // REQUIRES_NEW: every held-tip state change commits on its own. Once Stripe
    // has moved money, the matching DB write must not be rolled back by some
    // unrelated failure in the caller's transaction (e.g. account deletion).
    private val tx = TransactionTemplate(txManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    companion object {
        /** Statuses whose money is still "parked" for a receiver. */
        val OPEN_STATUSES = listOf(
            HeldTipStatus.AWAITING_PAYMENT,
            HeldTipStatus.HELD,
            HeldTipStatus.RELEASING,
            HeldTipStatus.BLOCKED
        )
        private val STUCK_AFTER: Duration = Duration.ofMinutes(15)
    }

    @PostConstruct
    fun checkConfig() {
        require(hmacSecret.length >= 32) {
            "tips.hold.hmacSecret must be set (>= 32 chars) — held tips cannot be integrity-protected without it"
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Creating a hold (called from TipController inside its transaction)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Returns a TipResult status code if the hold must be refused, or null if
     * it's allowed. Caller must hold a row lock on the receiver
     * (AppUserRepository.findByIdForUpdate) so concurrent tips can't race past
     * the per-receiver cap.
     */
    fun rejectionReason(senderId: UUID, receiver: AppUser, fee: FeeBreakdown): String? {
        val grossCents = fee.senderPaysCents
        if (fee.receiverGetsCents <= 0 || fee.receiverGetsCents >= grossCents) return "AMOUNT_TOO_SMALL"
        if (receiver.suspended) return "RECEIVER_UNAVAILABLE"

        // Receiver must be a real app install with a registered device key —
        // stops tips being parked against throwaway / scripted accounts.
        if (devices.findAllByUser_Id(receiver.id).isEmpty()) return "RECEIVER_UNAVAILABLE"

        val pendingCents = heldTips.sumGrossCentsForReceiver(receiver.id, OPEN_STATUSES)
        val pendingCount = heldTips.countForReceiver(receiver.id, OPEN_STATUSES)
        if (pendingCents + grossCents > maxPendingPerReceiverCents || pendingCount >= maxPendingCountPerReceiver) {
            log.warn("⛔ Hold cap hit for receiver ${receiver.id}: pending=$pendingCents¢/$pendingCount tips")
            return "RECEIVER_HOLD_LIMIT"
        }

        val senderDay = heldTips.sumGrossCentsForSenderSince(
            senderId, Instant.now().minus(Duration.ofDays(1)), HeldTipStatus.FAILED
        )
        if (senderDay + grossCents > maxPerSenderPerDayCents) {
            log.warn("⛔ Sender $senderId daily hold cap hit: $senderDay¢ in last 24h")
            return "SENDER_HOLD_LIMIT"
        }
        return null
    }

    fun createHold(tip: Tip, fee: FeeBreakdown, senderDeviceId: UUID?): HeldTip {
        val now = Instant.now()
        val held = HeldTip(
            tip              = tip,
            senderId         = tip.sender?.id,
            receiverId       = tip.receiver!!.id,
            grossCents       = fee.senderPaysCents,
            netCents         = fee.receiverGetsCents,
            platformFeeCents = fee.totalFeeCents,
            nonce            = tip.nonce,
            senderDeviceId   = senderDeviceId,
            transferGroup    = "tip_${tip.id}",
            status           = HeldTipStatus.AWAITING_PAYMENT,
            createdAt        = now,
            expiresAt        = now.plus(Duration.ofDays(expiryDays)),
            updatedAt        = now
        )
        held.integrityHash = computeHash(held)
        return heldTips.save(held)
    }

    // ════════════════════════════════════════════════════════════════════════
    // Webhook hooks — each returns true if the tip was a held tip and was handled
    // ════════════════════════════════════════════════════════════════════════

    fun isHeld(tipId: UUID): Boolean = heldTips.findByTipId(tipId) != null

    /** charge.succeeded (or synchronous PI success) → money is now parked. */
    fun onChargeSucceeded(tip: Tip, chargeId: String): Boolean {
        val handled = tx.execute {
            val held = heldTips.findByTipId(tip.id) ?: return@execute false
            if (held.status == HeldTipStatus.AWAITING_PAYMENT) {
                held.status  = HeldTipStatus.HELD
                held.heldAt  = Instant.now()
            }
            if (held.chargeId == null) held.chargeId = chargeId
            held.updatedAt = Instant.now()
            heldTips.save(held)

            val t = tips.findById(tip.id).orElseThrow()
            if (t.status == TipStatus.PENDING) t.status = TipStatus.HELD
            t.chargeId  = chargeId
            t.updatedAt = Instant.now()
            tips.save(t)
            true
        } ?: false

        if (handled) {
            // Receiver may have finished onboarding while the charge was in flight
            tip.receiver?.id?.let { rid ->
                if (users.findById(rid).map { it.stripeOnboarded }.orElse(false)) releaseForReceiver(rid)
            }
        }
        return handled
    }

    fun onPaymentFailed(tip: Tip, reason: String?): Boolean = tx.execute {
        val held = heldTips.findByTipId(tip.id) ?: return@execute false
        if (held.status == HeldTipStatus.AWAITING_PAYMENT) {
            held.status    = HeldTipStatus.FAILED
            held.lastError = reason
            held.updatedAt = Instant.now()
            heldTips.save(held)
        }
        true
    } ?: false

    /** Dispute / Radar review → freeze. If already paid out, claw back from the receiver. */
    fun onDisputeOrReview(tip: Tip, reason: String): Boolean {
        val held = heldTips.findByTipId(tip.id) ?: return false
        when (held.status) {
            HeldTipStatus.AWAITING_PAYMENT, HeldTipStatus.HELD, HeldTipStatus.RELEASING ->
                block(held.id, held.status, reason)
            HeldTipStatus.RELEASED -> reverseReleased(held, reason)
            else -> log.info("ℹ️ Dispute on held tip ${held.id} in status ${held.status} — nothing to do")
        }
        return true
    }

    /** Dispute closed. Won → back to HELD so it can be released; lost → REFUNDED. */
    fun onDisputeClosed(tip: Tip, won: Boolean): Boolean = tx.execute {
        val held = heldTips.findByTipId(tip.id) ?: return@execute false
        val t = tips.findById(tip.id).orElseThrow()
        if (held.status == HeldTipStatus.BLOCKED) {
            held.status      = if (won) HeldTipStatus.HELD else HeldTipStatus.REFUNDED
            held.blockReason = if (won) null else held.blockReason
            if (!won) held.refundedAt = Instant.now()
            held.updatedAt   = Instant.now()
            heldTips.save(held)
            t.status = if (won) TipStatus.HELD else TipStatus.REFUNDED
        } else if (held.status == HeldTipStatus.RELEASED) {
            t.status = if (won) TipStatus.SUCCEEDED else TipStatus.REFUNDED
        }
        t.updatedAt = Instant.now()
        tips.save(t)
        true
    } ?: false

    /** charge.refunded (manual refund from Dashboard, or our own expiry refund). */
    fun onChargeRefunded(tip: Tip): Boolean {
        val held = heldTips.findByTipId(tip.id) ?: return false
        when (held.status) {
            HeldTipStatus.RELEASED -> reverseReleased(held, "charge_refunded")
            HeldTipStatus.REFUNDED -> Unit
            else -> tx.execute {
                val h = heldTips.findById(held.id).orElseThrow()
                h.status     = HeldTipStatus.REFUNDED
                h.refundedAt = h.refundedAt ?: Instant.now()
                h.updatedAt  = Instant.now()
                heldTips.save(h)
            }
        }
        tx.execute {
            val t = tips.findById(tip.id).orElseThrow()
            t.status = TipStatus.REFUNDED
            t.updatedAt = Instant.now()
            tips.save(t)
        }
        return true
    }

    // ════════════════════════════════════════════════════════════════════════
    // Release (receiver finished onboarding)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Transfers every HELD tip for this receiver to their connected account.
     * Safe to call often and from anywhere (status poll, account.updated
     * webhook, scheduler): it exits cheaply when nothing is held.
     */
    fun releaseForReceiver(receiverId: UUID): Int {
        // Right after onboarding, the app's status poll AND the account.updated
        // webhook (and possibly the scheduler) all call this at the same time.
        // The per-row claim already stops double payment, but the callers used
        // to SPLIT the tips between them — e.g. 6 + 21 — each sending its own
        // "Your held tips (N)" push, and the second push replaced the first on
        // the phone. One release per receiver at a time: a concurrent caller
        // just returns, and the running one sweeps up everything.
        val lock = releaseLocks.computeIfAbsent(receiverId) { ReentrantLock() }
        if (!lock.tryLock()) {
            log.info("⏭️ Release already running for receiver $receiverId — skipping duplicate trigger")
            return 0
        }
        try {
            return releaseAllLocked(receiverId)
        } finally {
            lock.unlock()
        }
    }

    /** Per-receiver guard for releaseForReceiver (single server instance). */
    private val releaseLocks = ConcurrentHashMap<UUID, ReentrantLock>()

    private fun releaseAllLocked(receiverId: UUID): Int {
        var candidates = heldTips.findAllByReceiverIdAndStatus(receiverId, HeldTipStatus.HELD)
            .filter { it.chargeId != null }
        if (candidates.isEmpty()) return 0

        val receiver = users.findById(receiverId).orElse(null) ?: return 0
        val accountId = receiver.stripeAccountId ?: return 0
        if (!stripe.isAccountReadyForTransfers(accountId, receiverId)) {
            log.info("⏳ Receiver $receiverId has held tips but account $accountId isn't transfer-ready yet")
            return 0
        }

        var releasedNetCents = 0L
        var releasedCount = 0
        val firstTipId = candidates.first().tip.id
        // Loop until nothing is left, so a tip whose charge settled while we
        // were releasing is included in this same batch and notification.
        while (candidates.isNotEmpty()) {
            candidates.forEach { h ->
                if (releaseOne(h.id, accountId)) {
                    releasedCount++
                    releasedNetCents += h.netCents
                }
            }
            candidates = heldTips.findAllByReceiverIdAndStatus(receiverId, HeldTipStatus.HELD)
                .filter { it.chargeId != null && it.releaseAttempts == 0 }   // don't spin on failing rows
        }
        if (releasedCount > 0) {
            log.info("💸 Released $releasedCount held tips ($releasedNetCents¢) to receiver $receiverId")
            // Reuses the existing FCM "tip" push so no client change is needed
            // to show it; senderName carries the context.
            try {
                notifications.notifyTipReceived(
                    receiverId  = receiverId,
                    senderId    = receiverId,
                    senderName  = "Your held tips ($releasedCount) are on the way to your bank",
                    amountCents = releasedNetCents,
                    tipId       = firstTipId
                )
            } catch (e: Exception) {
                log.warn("⚠️ Release notification failed: ${e.message}")
            }
        }
        return releasedCount
    }

    private fun releaseOne(heldId: UUID, accountId: String): Boolean {
        val claimed = tx.execute {
            heldTips.transition(heldId, HeldTipStatus.HELD, HeldTipStatus.RELEASING, Instant.now()) == 1
        } ?: false
        if (!claimed) return false

        val held = heldTips.findWithTip(heldId) ?: return false
        val tip  = held.tip

        integrityProblem(held, tip)?.let { problem ->
            log.error("🚨 INTEGRITY CHECK FAILED for held tip $heldId: $problem — blocking payout")
            block(heldId, HeldTipStatus.RELEASING, "INTEGRITY: $problem")
            return false
        }

        return try {
            val transfer = stripe.createHeldTipTransfer(
                netCents             = held.netCents,
                destinationAccountId = accountId,
                sourceChargeId       = held.chargeId!!,
                transferGroup        = held.transferGroup,
                heldTipId            = held.id,
                tipId                = tip.id,
                receiverId           = held.receiverId
            )
            markReleased(heldId, transfer.id, accountId)
            true
        } catch (e: Exception) {
            tx.execute {
                val h = heldTips.findById(heldId).orElseThrow()
                h.releaseAttempts += 1
                h.lastError = e.message?.take(1000)
                h.status = if (h.releaseAttempts >= maxReleaseAttempts) HeldTipStatus.BLOCKED else HeldTipStatus.HELD
                if (h.status == HeldTipStatus.BLOCKED) h.blockReason = "RELEASE_FAILED_MAX_ATTEMPTS"
                h.updatedAt = Instant.now()
                heldTips.save(h)
            }
            false
        }
    }

    private fun markReleased(heldId: UUID, transferId: String, accountId: String) {
        tx.execute {
            val h = heldTips.findWithTip(heldId)!!
            h.status               = HeldTipStatus.RELEASED
            h.transferId           = transferId
            h.destinationAccountId = accountId
            h.releasedAt           = Instant.now()
            h.lastError            = null
            h.updatedAt            = Instant.now()
            heldTips.save(h)

            val t = tips.findById(h.tip.id).orElseThrow()
            t.status    = TipStatus.SUCCEEDED
            t.updatedAt = Instant.now()
            tips.save(t)
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Refund (expiry / receiver deleted account)
    // ════════════════════════════════════════════════════════════════════════

    fun refundHeld(heldId: UUID, reason: String): Boolean {
        val claimed = tx.execute {
            heldTips.transition(heldId, HeldTipStatus.HELD, HeldTipStatus.REFUNDING, Instant.now()) == 1
        } ?: false
        if (!claimed) return false

        val held = heldTips.findWithTip(heldId) ?: return false
        return try {
            val refund = stripe.refundHeldCharge(held.chargeId!!, held.id, reason)
            tx.execute {
                val h = heldTips.findWithTip(heldId)!!
                h.status     = HeldTipStatus.REFUNDED
                h.refundId   = refund.id
                h.refundedAt = Instant.now()
                h.blockReason = reason
                h.updatedAt  = Instant.now()
                heldTips.save(h)
                val t = tips.findById(h.tip.id).orElseThrow()
                t.status = TipStatus.REFUNDED
                t.failureReason = "Held tip refunded: $reason"
                t.updatedAt = Instant.now()
                tips.save(t)
            }
            log.info("↩️ Refunded held tip $heldId ($reason)")
            true
        } catch (e: Exception) {
            tx.execute {
                val h = heldTips.findById(heldId).orElseThrow()
                h.status    = HeldTipStatus.HELD
                h.lastError = e.message?.take(1000)
                h.updatedAt = Instant.now()
                heldTips.save(h)
            }
            false
        }
    }

    /** Receiver is deleting their account — give every parked tip back to its sender. */
    fun refundAllForReceiver(receiverId: UUID, reason: String): Int =
        heldTips.findAllByReceiverIdAndStatus(receiverId, HeldTipStatus.HELD)
            .count { refundHeld(it.id, reason) }

    // ════════════════════════════════════════════════════════════════════════
    // Scheduler
    // ════════════════════════════════════════════════════════════════════════

    @Scheduled(fixedDelayString = "\${tips.hold.sweepIntervalMs:900000}", initialDelay = 60_000)
    fun sweep() {
        try {
            recoverStuck()
            heldTips.findOnboardedReceiversWithStatus(HeldTipStatus.HELD).forEach { releaseForReceiver(it) }
            heldTips.findExpiredIds(Instant.now(), HeldTipStatus.HELD).forEach { refundHeld(it, "EXPIRED_UNCLAIMED") }
        } catch (e: Exception) {
            log.error("❌ Held-tip sweep failed", e)
        }
    }

    /**
     * A crash between "Stripe call succeeded" and "DB updated" leaves a row in
     * RELEASING/REFUNDING. Ask Stripe what actually happened instead of
     * blindly retrying (idempotency keys only last ~24h).
     */
    private fun recoverStuck() {
        val before = Instant.now().minus(STUCK_AFTER)

        heldTips.findStuckIds(HeldTipStatus.RELEASING, before).forEach { id ->
            val h = heldTips.findWithTip(id) ?: return@forEach
            val existing = stripe.findTransferByGroup(h.transferGroup)
            if (existing != null) {
                log.warn("🔧 Recovered RELEASING held tip $id — transfer ${existing.id} already exists")
                markReleased(id, existing.id, existing.destination)
            } else {
                tx.execute { heldTips.transition(id, HeldTipStatus.RELEASING, HeldTipStatus.HELD, Instant.now()) }
            }
        }

        heldTips.findStuckIds(HeldTipStatus.REFUNDING, before).forEach { id ->
            // refundHeldCharge is idempotent per held tip; put it back to HELD
            // and let the expiry pass re-run it with the same key/outcome.
            tx.execute { heldTips.transition(id, HeldTipStatus.REFUNDING, HeldTipStatus.HELD, Instant.now()) }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Receiver summary for the app
    // ════════════════════════════════════════════════════════════════════════

    data class HeldSummary(
        val pendingNetCents: Long,
        val pendingCount: Long,
        val earliestExpiresAt: String?,
        val expiryDays: Long
    )

    fun summaryFor(receiverId: UUID): HeldSummary {
        val statuses = listOf(HeldTipStatus.AWAITING_PAYMENT, HeldTipStatus.HELD, HeldTipStatus.RELEASING)
        return HeldSummary(
            pendingNetCents   = heldTips.sumNetCentsForReceiver(receiverId, statuses),
            pendingCount      = heldTips.countForReceiver(receiverId, statuses),
            earliestExpiresAt = heldTips.earliestExpiryForReceiver(receiverId, HeldTipStatus.HELD)?.toString(),
            expiryDays        = expiryDays
        )
    }

    // ════════════════════════════════════════════════════════════════════════
    // Internals
    // ════════════════════════════════════════════════════════════════════════

    private fun block(heldId: UUID, from: HeldTipStatus, reason: String) {
        tx.execute {
            if (heldTips.transition(heldId, from, HeldTipStatus.BLOCKED, Instant.now()) == 1) {
                val h = heldTips.findById(heldId).orElseThrow()
                h.blockReason = reason.take(255)
                heldTips.save(h)
                log.warn("🧊 Held tip $heldId BLOCKED: $reason")
            }
        }
    }

    private fun reverseReleased(held: HeldTip, reason: String) {
        val transferId = held.transferId ?: return
        try {
            stripe.reverseTransfer(transferId, held.netCents, held.id)
            log.warn("↩️ Reversed transfer $transferId for held tip ${held.id} ($reason)")
        } catch (e: Exception) {
            // Receiver balance may already be paid out — needs a human
            log.error("🚨 MANUAL ACTION: could not reverse $transferId for held tip ${held.id}: ${e.message}")
            tx.execute {
                val h = heldTips.findById(held.id).orElseThrow()
                h.lastError = "REVERSAL_FAILED: ${e.message}".take(1000)
                h.updatedAt = Instant.now()
                heldTips.save(h)
            }
        }
    }

    /** Null means OK. Anything else is a reason not to move money. */
    private fun integrityProblem(h: HeldTip, tip: Tip): String? {
        if (!MessageDigest.isEqual(computeHash(h).toByteArray(), h.integrityHash.toByteArray()))
            return "hash mismatch"
        if (tip.receiver?.id != h.receiverId) return "receiver mismatch"
        if (tip.nonce != h.nonce) return "nonce mismatch"
        if (stripe.toCents(tip.amount) != h.grossCents) return "amount mismatch"
        if (h.chargeId == null || tip.chargeId != h.chargeId) return "charge mismatch"
        if (h.netCents <= 0 || h.netCents >= h.grossCents) return "bad net amount"
        return null
    }

    // senderId is intentionally NOT hashed: it is nulled out if the sender
    // deletes their account, and that must not freeze the receiver's money.
    private fun computeHash(h: HeldTip): String {
        val payload = listOf(
            "v1", h.id, h.tip.id, h.receiverId,
            h.grossCents, h.netCents, h.platformFeeCents, h.currency,
            h.nonce, h.transferGroup, h.createdAt.toEpochMilli()
        ).joinToString("|")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(hmacSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}