package com.taptaptips.server.web

import com.taptaptips.server.domain.Tip
import com.taptaptips.server.repo.AppUserRepository
import com.taptaptips.server.repo.TipRepository
import com.taptaptips.server.service.TipSecurityService
import com.taptaptips.server.service.TipSecurityService.AlreadyProcessed
import com.taptaptips.server.service.NotificationService
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
    private val notificationService: NotificationService
) {
    companion object {
        private const val DEFAULT_PAGE_SIZE = 50
        private const val MAX_PAGE_SIZE = 200
    }

    private fun authUserId(): UUID =
        UUID.fromString(SecurityContextHolder.getContext().authentication.name)

    @PostMapping
    @Transactional
    fun create(@RequestBody req: CreateTipRequest): TipResult {
        val auth = authUserId()
        val v = try { security.verifyRequest(req, auth) }
                catch (e: AlreadyProcessed) { return TipResult("DUPLICATE") }

        val sender = users.findById(v.senderId).orElseThrow()
        val receiver = users.findById(v.receiverId).orElseThrow()

        return try {
            val now = Instant.now()
            val userZone = ZoneId.of(req.timezone)
            val localDate = now.atZone(userZone).toLocalDate()

            val feeBreakdown = stripeService.calculateFeeBreakdown(v.amount)

            val tip = tips.save(
                Tip(
                    sender = sender,
                    receiver = receiver,
                    amount = v.amount,
                    netAmount = feeBreakdown.receiverGets,
                    totalFees = feeBreakdown.totalFee,
                    platformFee = feeBreakdown.platformFee,
                    createdAt = now,
                    createdAtLocal = localDate,
                    timezone = req.timezone,
                    nonce = v.nonce,
                    timestamp = v.timestamp,
                    verified = true
                )
            )

            notificationService.notifyTipReceived(
                receiverId  = receiver.id!!,
                senderId    = sender.id!!,
                senderName  = sender.displayName,
                amountCents = (feeBreakdown.receiverGets * java.math.BigDecimal(100)).toLong(),
                tipId       = tip.id!!
            )

            TipResult("CONFIRMED")
        } catch (e: DataIntegrityViolationException) {
            TipResult("DUPLICATE")
        }
    }

    /**
     * GET /tips/received
     *
     * Query params:
     *   startDate + endDate  → inclusive range (ISO dates: yyyy-MM-dd)
     *   date                 → single day, defaults to today
     *   page                 → 0-based page index (default 0)
     *   size                 → page size, capped at MAX_PAGE_SIZE (default 50)
     *
     * FIX: uses FETCH JOIN queries — sender/receiver loaded in the same SQL
     * statement as the tips. No N+1 lazy loads, no unbounded result sets.
     */
    @GetMapping("/received")
    fun getReceivedTips(
        @RequestParam(required = false) date: String?,
        @RequestParam(required = false) startDate: String?,
        @RequestParam(required = false) endDate: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "$DEFAULT_PAGE_SIZE") size: Int
    ): TipPageResult {
        val receiverId = authUserId()
        val pageable = PageRequest.of(page, size.coerceAtMost(MAX_PAGE_SIZE), Sort.by("createdAt").descending())

        val tipPage = when {
            startDate != null && endDate != null ->
                tips.findByReceiver_IdAndCreatedAtLocalBetween(
                    receiverId,
                    LocalDate.parse(startDate),
                    LocalDate.parse(endDate),
                    pageable
                )
            else ->
                tips.findByReceiver_IdAndCreatedAtLocal(
                    receiverId,
                    date?.let { LocalDate.parse(it) } ?: LocalDate.now(),
                    pageable
                )
        }

        return TipPageResult(
            tips = tipPage.content.map { it.toReceivedDto() },
            totalElements = tipPage.totalElements,
            totalPages = tipPage.totalPages,
            page = page,
            size = tipPage.numberOfElements
        )
    }

    /**
     * GET /tips/sent
     *
     * FIX: same FETCH JOIN + pagination treatment as /received.
     */
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
            tips = tipPage.content.map { it.toSentDto() },
            totalElements = tipPage.totalElements,
            totalPages = tipPage.totalPages,
            page = page,
            size = tipPage.numberOfElements
        )
    }

    // ── Mapping helpers ──────────────────────────────────────────────────────
    // Defined as extension funs so the controller stays readable and the
    // mapping is co-located with the types it touches.

    private fun Tip.toReceivedDto() = TipSummaryDto(
        id             = id.toString(),
        senderId       = sender?.id?.toString() ?: "",
        senderName     = sender?.displayName    ?: "Unknown",
        amount         = amount,
        netAmount      = netAmount,
        totalFees      = totalFees,
        platformFee    = platformFee,
        createdAt      = createdAt.toString(),
        createdAtLocal = createdAtLocal.toString(),
        timezone       = timezone
    )

    private fun Tip.toSentDto() = SentTipSummaryDto(
        id            = id.toString(),
        recipientId   = receiver?.id?.toString() ?: "",
        recipientName = receiver?.displayName    ?: "Unknown",
        amount        = amount,
        netAmount     = netAmount,
        totalFees     = totalFees,
        platformFee   = platformFee,
        createdAt     = createdAt.toString(),
        createdAtLocal = createdAtLocal.toString(),
        timezone      = timezone
    )
}

// ── Response types ───────────────────────────────────────────────────────────

data class TipPageResult(
    val tips: List<Any>,          // TipSummaryDto or SentTipSummaryDto
    val totalElements: Long,
    val totalPages: Int,
    val page: Int,
    val size: Int
)

data class TipSummaryDto(
    val id: String,
    val senderId: String,
    val senderName: String,
    val amount: BigDecimal,
    val netAmount: BigDecimal? = null,
    val totalFees: BigDecimal? = null,
    val platformFee: BigDecimal? = null,
    val createdAt: String,
    val createdAtLocal: String,
    val timezone: String
)

data class SentTipSummaryDto(
    val id: String,
    val recipientId: String,
    val recipientName: String,
    val amount: BigDecimal,
    val netAmount: BigDecimal? = null,
    val totalFees: BigDecimal? = null,
    val platformFee: BigDecimal? = null,
    val createdAt: String,
    val createdAtLocal: String,
    val timezone: String
)