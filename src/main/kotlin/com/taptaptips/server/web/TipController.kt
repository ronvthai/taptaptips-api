package com.taptaptips.server.web

import com.taptaptips.server.domain.Tip
import com.taptaptips.server.domain.TipStatus
import com.taptaptips.server.domain.HeldTipStatus
import com.taptaptips.server.repo.HeldTipRepository
import com.taptaptips.server.service.HeldTipService
import com.taptaptips.server.repo.AppUserRepository
import com.taptaptips.server.repo.TipRepository
import com.taptaptips.server.service.TipSecurityService
import com.taptaptips.server.service.TipSecurityService.AlreadyProcessed
import com.taptaptips.server.service.NotificationService
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@RestController
@RequestMapping("/tips")
class TipController(
    private val tips: TipRepository,
    private val users: AppUserRepository,
    private val security: TipSecurityService,
    private val stripeService: com.taptaptips.server.service.StripePaymentService,
    private val notificationService: NotificationService,
    private val heldTipService: HeldTipService,
    private val heldTips: HeldTipRepository
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val DEFAULT_PAGE_SIZE = 50
        private const val MAX_PAGE_SIZE     = 200
    }

    private fun authUserId(): UUID =
        UUID.fromString(SecurityContextHolder.getContext().authentication.name)

    /**
     * POST /tips
     *
     * FIX — Orphan tip on Stripe failure:
     *   Tip is saved as PENDING, then Stripe is called inside a try/catch.
     *   On any Stripe exception the tip is immediately marked FAILED — no orphan
     *   PENDING rows that will never settle.
     *
     * FIX — Double FCM notification:
     *   The immediate notifyTipReceived() call has been removed.
     *   StripeWebhookController.handleChargeSucceeded() is the single notification
     *   path. It fires after Stripe confirms funds are captured — the correct moment
     *   to tell the receiver money is on the way. The old pre-confirm notification
     *   fired on tips that could still fail (declined cards, etc.).
     */
    @PostMapping
    @Transactional
    fun create(@RequestBody req: CreateTipRequest): TipResult {
        val auth = authUserId()
        val v = try { security.verifyRequest(req, auth) }
                catch (e: AlreadyProcessed) { return TipResult("DUPLICATE") }

        val sender   = users.findById(v.senderId).orElseThrow()
        val receiver = users.findById(v.receiverId).orElseThrow()

        val senderCustomerId = sender.stripeCustomerId
            ?: return TipResult("SENDER_NOT_SETUP")
        val paymentMethodId = sender.defaultPaymentMethodId
            ?: return TipResult("NO_PAYMENT_METHOD")

        // Receiver fully onboarded → existing destination-charge flow (money
        // goes straight to their connected account). Otherwise → held flow.
        // NOTE: previously this only checked stripeAccountId != null, but an
        // Express account exists as soon as registration starts onboarding, so
        // half-onboarded receivers fell into the destination path and failed.
        val receiverStripeAccountId = receiver.stripeAccountId
        if (!receiver.stripeOnboarded || receiverStripeAccountId == null) {
            return createHeldTip(req, v, sender, senderCustomerId, paymentMethodId)
        }

        return try {
            val now          = Instant.now()
            val localDate    = now.atZone(ZoneId.of(req.timezone)).toLocalDate()
            val feeBreakdown = stripeService.calculateFeeBreakdown(v.amount)

            val tip = tips.save(
                Tip(
                    sender         = sender,
                    receiver       = receiver,
                    amount         = v.amount,
                    netAmount      = feeBreakdown.receiverGets,
                    totalFees      = feeBreakdown.totalFee,
                    platformFee    = feeBreakdown.platformFee,
                    createdAt      = now,
                    createdAtLocal = localDate,
                    timezone       = req.timezone,
                    nonce          = v.nonce,
                    timestamp      = v.timestamp,
                    verified       = true,
                    status         = TipStatus.PENDING
                )
            )

            // Stripe call wrapped — any failure marks the tip FAILED immediately
            val paymentIntent = try {
                stripeService.createPaymentIntentWithSavedMethod(
                    amount                  = v.amount,
                    receiverStripeAccountId = receiverStripeAccountId,
                    senderId                = v.senderId,
                    receiverId              = v.receiverId,
                    tipNonce                = v.nonce,
                    paymentMethodId         = paymentMethodId,
                    senderCustomerId        = senderCustomerId
                )
            } catch (e: Exception) {
                log.error("❌ Stripe PaymentIntent failed for tip ${tip.id}: ${e.message}")
                tip.status        = TipStatus.FAILED
                tip.failureReason = e.message
                tip.updatedAt     = Instant.now()
                tips.save(tip)
                return TipResult("PAYMENT_FAILED")
            }

            tip.paymentIntentId = paymentIntent.id
            tip.updatedAt       = Instant.now()
            tips.save(tip)

            // Notification intentionally removed — webhook fires after Stripe confirms

            TipResult("CONFIRMED")
        } catch (e: DataIntegrityViolationException) {
            TipResult("DUPLICATE")
        }
    }

    /**
     * Receiver hasn't linked a bank yet. Charge the sender on the platform
     * account and park the money in a HeldTip row; HeldTipService transfers it
     * once the receiver finishes Stripe onboarding (or refunds it on expiry).
     *
     * Returns "HELD" on success so the client can tell the sender the tip went
     * through but will be paid out once the recipient links their bank.
     */
    private fun createHeldTip(
        req: CreateTipRequest,
        v: TipSecurityService.VerifiedInput,
        sender: com.taptaptips.server.domain.AppUser,
        senderCustomerId: String,
        paymentMethodId: String
    ): TipResult {
        // Row lock on the receiver: serialises concurrent held tips to the same
        // person so the holding cap can't be raced.
        val receiver = users.findByIdForUpdate(v.receiverId) ?: return TipResult("RECEIVER_UNAVAILABLE")

        val feeBreakdown = stripeService.calculateFeeBreakdown(v.amount)
        heldTipService.rejectionReason(sender.id, receiver, feeBreakdown)
            ?.let { return TipResult(it) }

        return try {
            val now       = Instant.now()
            val localDate = now.atZone(ZoneId.of(req.timezone)).toLocalDate()

            val tip = tips.save(
                Tip(
                    sender         = sender,
                    receiver       = receiver,
                    amount         = v.amount,
                    netAmount      = feeBreakdown.receiverGets,
                    totalFees      = feeBreakdown.totalFee,
                    platformFee    = feeBreakdown.platformFee,
                    createdAt      = now,
                    createdAtLocal = localDate,
                    timezone       = req.timezone,
                    nonce          = v.nonce,
                    timestamp      = v.timestamp,
                    verified       = true,
                    status         = TipStatus.PENDING
                )
            )
            val held = heldTipService.createHold(tip, feeBreakdown, v.verifiedDeviceId)

            val paymentIntent = try {
                stripeService.createHeldPaymentIntent(
                    amount           = v.amount,
                    senderId         = v.senderId,
                    receiverId       = v.receiverId,
                    tipId            = tip.id,
                    heldTipId        = held.id,
                    tipNonce         = v.nonce,
                    transferGroup    = held.transferGroup,
                    paymentMethodId  = paymentMethodId,
                    senderCustomerId = senderCustomerId
                )
            } catch (e: Exception) {
                log.error("❌ Held-tip PaymentIntent failed for tip ${tip.id}: ${e.message}")
                tip.status        = TipStatus.FAILED
                tip.failureReason = e.message
                tip.updatedAt     = Instant.now()
                tips.save(tip)
                held.status    = HeldTipStatus.FAILED
                held.lastError = e.message?.take(1000)
                held.updatedAt = Instant.now()
                heldTips.save(held)
                return TipResult("PAYMENT_FAILED")
            }

            tip.paymentIntentId  = paymentIntent.id
            tip.updatedAt        = Instant.now()
            held.paymentIntentId = paymentIntent.id
            held.updatedAt       = Instant.now()

            // Off-session confirm usually succeeds synchronously; record the
            // charge now. charge.succeeded will arrive later and is idempotent.
            if (paymentIntent.status == "succeeded" && paymentIntent.latestCharge != null) {
                tip.chargeId  = paymentIntent.latestCharge
                tip.status    = TipStatus.HELD
                held.chargeId = paymentIntent.latestCharge
                held.status   = HeldTipStatus.HELD
                held.heldAt   = Instant.now()
            }
            tips.save(tip)
            heldTips.save(held)

            log.info("🅿️ Tip ${tip.id} HELD for receiver ${receiver.id} (${feeBreakdown.receiverGetsCents}¢ net)")
            TipResult("HELD")
        } catch (e: DataIntegrityViolationException) {
            TipResult("DUPLICATE")
        }
    }

    /** GET /tips/held/summary — what's waiting for the caller once they link a bank. */
    @GetMapping("/held/summary")
    fun heldSummary(): HeldTipService.HeldSummary = heldTipService.summaryFor(authUserId())

    @GetMapping("/received")
    fun getReceivedTips(
        @RequestParam(required = false) date: String?,
        @RequestParam(required = false) startDate: String?,
        @RequestParam(required = false) endDate: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "$DEFAULT_PAGE_SIZE") size: Int
    ): TipPageResult {
        val receiverId = authUserId()
        val pageable   = PageRequest.of(page, size.coerceAtMost(MAX_PAGE_SIZE), Sort.by("createdAt").descending())

        val tipPage = when {
            startDate != null && endDate != null ->
                tips.findByReceiver_IdAndCreatedAtLocalBetween(
                    receiverId, LocalDate.parse(startDate), LocalDate.parse(endDate), pageable
                )
            else ->
                tips.findByReceiver_IdAndCreatedAtLocal(
                    receiverId, date?.let { LocalDate.parse(it) } ?: LocalDate.now(), pageable
                )
        }

        return TipPageResult(
            tips          = tipPage.content.map { it.toReceivedDto() },
            totalElements = tipPage.totalElements,
            totalPages    = tipPage.totalPages,
            page          = page,
            size          = tipPage.numberOfElements
        )
    }

    @GetMapping("/sent")
    fun getSentTips(
        @RequestParam(required = false) startDate: String?,
        @RequestParam(required = false) endDate: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "$DEFAULT_PAGE_SIZE") size: Int
    ): TipPageResult {
        val senderId = authUserId()
        val pageable = PageRequest.of(page, size.coerceAtMost(MAX_PAGE_SIZE), Sort.by("createdAt").descending())

        val start = startDate?.let { LocalDate.parse(it) } ?: LocalDate.now().withDayOfMonth(1)
        val end   = endDate?.let   { LocalDate.parse(it) } ?: LocalDate.now()

        val tipPage = tips.findBySender_IdAndCreatedAtLocalBetween(senderId, start, end, pageable)

        return TipPageResult(
            tips          = tipPage.content.map { it.toSentDto() },
            totalElements = tipPage.totalElements,
            totalPages    = tipPage.totalPages,
            page          = page,
            size          = tipPage.numberOfElements
        )
    }

    private fun Tip.toReceivedDto() = TipSummaryDto(
        id               = id.toString(),
        senderId         = sender?.id?.toString()   ?: "",
        senderName       = sender?.displayName      ?: "Unknown",
        senderOnboarded  = sender?.stripeOnboarded  ?: false,
        amount           = amount,
        netAmount        = netAmount,
        totalFees        = totalFees,
        platformFee      = platformFee,
        createdAt        = createdAt.toString(),
        createdAtLocal   = createdAtLocal.toString(),
        timezone         = timezone,
        status           = status.name,
        pendingPayout    = status == TipStatus.HELD
    )

    private fun Tip.toSentDto() = SentTipSummaryDto(
        id             = id.toString(),
        recipientId    = receiver?.id?.toString() ?: "",
        recipientName  = receiver?.displayName   ?: "Unknown",
        amount         = amount,
        netAmount      = netAmount,
        totalFees      = totalFees,
        platformFee    = platformFee,
        createdAt      = createdAt.toString(),
        createdAtLocal = createdAtLocal.toString(),
        timezone       = timezone,
        status         = status.name,
        pendingPayout  = status == TipStatus.HELD
    )
}

data class TipPageResult(
    val tips: List<Any>,
    val totalElements: Long,
    val totalPages: Int,
    val page: Int,
    val size: Int
)

data class TipSummaryDto(
    val id: String,
    val senderId: String,
    val senderName: String,
    /**
     * Whether the sender can currently receive tips (has completed Stripe
     * Connect onboarding). Used by the client's Received tab to gate the
     * "Send Tip Back" button — no point offering the button if the tip
     * would end up held (see HeldTip) instead of paid out right away.
     *
     * Read from the cached `stripeOnboarded` flag on the User entity — no
     * live Stripe API call, so this is cheap to include per row.
     */
    val senderOnboarded: Boolean = false,
    val amount: BigDecimal,
    val netAmount: BigDecimal?   = null,
    val totalFees: BigDecimal?   = null,
    val platformFee: BigDecimal? = null,
    val createdAt: String,
    val createdAtLocal: String,
    val timezone: String,
    /** TipStatus name — PENDING | HELD | SUCCEEDED | FAILED | DISPUTED | REFUNDED */
    val status: String = "SUCCEEDED",
    /** True while the money is parked waiting for the receiver to link a bank. */
    val pendingPayout: Boolean = false
)

data class SentTipSummaryDto(
    val id: String,
    val recipientId: String,
    val recipientName: String,
    val amount: BigDecimal,
    val netAmount: BigDecimal?   = null,
    val totalFees: BigDecimal?   = null,
    val platformFee: BigDecimal? = null,
    val createdAt: String,
    val createdAtLocal: String,
    val timezone: String,
    /** TipStatus name — PENDING | HELD | SUCCEEDED | FAILED | DISPUTED | REFUNDED */
    val status: String = "SUCCEEDED",
    /** True while the money is parked waiting for the receiver to link a bank. */
    val pendingPayout: Boolean = false
)