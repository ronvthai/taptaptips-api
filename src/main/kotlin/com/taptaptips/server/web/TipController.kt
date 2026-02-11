package com.taptaptips.server.web

import com.taptaptips.server.domain.Tip
import com.taptaptips.server.repo.AppUserRepository
import com.taptaptips.server.repo.TipRepository
import com.taptaptips.server.service.TipSecurityService
import com.taptaptips.server.service.TipSecurityService.AlreadyProcessed
import com.taptaptips.server.service.NotificationService
import com.taptaptips.server.service.StripePaymentService  // ⭐ NEW: Import for fee calculation
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
    private val notificationService: NotificationService,
    private val stripeService: StripePaymentService  // ⭐ NEW: Inject StripePaymentService
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
        
        // ⭐ NEW: Calculate fee breakdown
        val feeBreakdown = stripeService.calculateFeeBreakdown(v.amount)
        
        val tip = tips.save(
            Tip(
                sender = sender,
                receiver = receiver,
                amount = v.amount,                          // Gross amount (what sender paid)
                netAmount = feeBreakdown.receiverGets,      // ⭐ NEW: Net amount (what receiver gets)
                totalFees = feeBreakdown.totalFee,          // ⭐ NEW: Total fees
                platformFee = feeBreakdown.platformFee,     // ⭐ NEW: Your 3% revenue
                createdAt = now,
                createdAtLocal = localDate,
                timezone = req.timezone,
                nonce = v.nonce,
                timestamp = v.timestamp,
                verified = true
            )
        )
        
        // ⭐ UPDATED: Send NET amount to notification (what receiver actually gets)
        notificationService.notifyTipReceived(
            receiverId = receiver.id!!,
            senderId = sender.id!!,
            senderName = sender.displayName,
            amount = feeBreakdown.receiverGets.toInt(),  // ⭐ CHANGED: Use net amount instead of gross
            tipId = tip.id!!
        )
        
        TipResult("CONFIRMED")
    } catch (e: DataIntegrityViolationException) {
        TipResult("DUPLICATE")
    }
}

    @GetMapping("/received")
fun getReceivedTips(
    @RequestParam(required = false) date: String?
): List<TipSummaryDto> {
    val receiverId: UUID = authUserId()
    
    val targetDate: LocalDate = date?.let { LocalDate.parse(it) } ?: LocalDate.now()
    
    val receivedTips: List<Tip> = tips.findByReceiver_IdAndCreatedAtLocal(
        receiverId, 
        targetDate
    )
    
    return receivedTips.map { tip: Tip ->
        TipSummaryDto(
            id = tip.id.toString(),
            senderId = tip.sender?.id?.toString() ?: "",
            senderName = tip.sender?.displayName ?: "Unknown",
            amount = tip.amount,                      // Gross amount
            netAmount = tip.netAmount,                // ⭐ NEW: Net amount
            totalFees = tip.totalFees,                // ⭐ NEW: Total fees
            platformFee = tip.platformFee,            // ⭐ NEW: Platform fee
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
    val amount: BigDecimal,              // Gross amount (what sender paid)
    val netAmount: BigDecimal?,          // ⭐ NEW: Net amount (what receiver gets)
    val totalFees: BigDecimal?,          // ⭐ NEW: Total fees deducted
    val platformFee: BigDecimal?,        // ⭐ NEW: Platform fee (your revenue)
    val createdAt: String,
    val createdAtLocal: String,
    val timezone: String
)

data class SentTipSummaryDto(
    val id: String,
    val recipientId: String,
    val recipientName: String,
    val amount: BigDecimal,
    val createdAt: String,
    val createdAtLocal: String,
    val timezone: String
)