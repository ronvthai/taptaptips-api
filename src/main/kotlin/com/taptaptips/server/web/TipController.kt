package com.taptaptips.server.web

import com.taptaptips.server.domain.Tip
import com.taptaptips.server.repo.AppUserRepository
import com.taptaptips.server.repo.TipRepository
import com.taptaptips.server.service.TipSecurityService
import com.taptaptips.server.service.TipSecurityService.AlreadyProcessed
import org.springframework.dao.DataIntegrityViolationException
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
    private val stripeService: com.taptaptips.server.service.StripePaymentService
) {
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

            TipResult("CONFIRMED")
        } catch (e: DataIntegrityViolationException) {
            TipResult("DUPLICATE")
        }
    }

    /**
     * GET /tips/received
     *
     * Query params (mutually exclusive priority):
     *   startDate + endDate  → inclusive range (ISO dates: yyyy-MM-dd)
     *   date                 → single day (ISO date: yyyy-MM-dd), defaults to today
     */
    @GetMapping("/received")
    fun getReceivedTips(
        @RequestParam(required = false) date: String?,
        @RequestParam(required = false) startDate: String?,
        @RequestParam(required = false) endDate: String?
    ): List<TipSummaryDto> {
        val receiverId: UUID = authUserId()

        val receivedTips: List<Tip> = when {
            startDate != null && endDate != null -> {
                val start = LocalDate.parse(startDate)
                val end   = LocalDate.parse(endDate)
                tips.findByReceiver_IdAndCreatedAtLocalBetween(receiverId, start, end)
            }
            else -> {
                val targetDate = date?.let { LocalDate.parse(it) } ?: LocalDate.now()
                tips.findByReceiver_IdAndCreatedAtLocal(receiverId, targetDate)
            }
        }

        return receivedTips.map { tip: Tip ->
            TipSummaryDto(
                id = tip.id.toString(),
                senderId = tip.sender?.id?.toString() ?: "",
                senderName = tip.sender?.displayName ?: "Unknown",
                amount = tip.amount,
                netAmount = tip.netAmount,
                totalFees = tip.totalFees,
                platformFee = tip.platformFee,
                createdAt = tip.createdAt.toString(),
                createdAtLocal = tip.createdAtLocal.toString(),
                timezone = tip.timezone
            )
        }
    }

    @GetMapping("/sent")
    fun getSentTips(
        @RequestParam(required = false) startDate: String?,
        @RequestParam(required = false) endDate: String?
    ): List<SentTipSummaryDto> {
        val senderId: UUID = authUserId()

        val start: LocalDate = startDate?.let { LocalDate.parse(it) }
            ?: LocalDate.now().withDayOfMonth(1)
        val end: LocalDate = endDate?.let { LocalDate.parse(it) }
            ?: LocalDate.now()

        val sentTips: List<Tip> = tips.findBySender_IdAndCreatedAtLocalBetween(
            senderId,
            start,
            end
        )

        return sentTips.map { tip: Tip ->
            SentTipSummaryDto(
                id = tip.id.toString(),
                recipientId = tip.receiver?.id?.toString() ?: "",
                recipientName = tip.receiver?.displayName ?: "Unknown",
                amount = tip.amount,
                netAmount = tip.netAmount,
                totalFees = tip.totalFees,
                platformFee = tip.platformFee,
                createdAt = tip.createdAt.toString(),
                createdAtLocal = tip.createdAtLocal.toString(),
                timezone = tip.timezone
            )
        }
    }
}

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