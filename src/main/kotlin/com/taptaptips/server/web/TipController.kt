package com.taptaptips.server.web

import com.taptaptips.server.domain.Tip
import com.taptaptips.server.repo.AppUserRepository
import com.taptaptips.server.repo.TipRepository
import com.taptaptips.server.service.TipSecurityService
import com.taptaptips.server.service.TipSecurityService.AlreadyProcessed
import com.taptaptips.server.service.NotificationService 
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
    private val stripeService: com.taptaptips.server.service.StripePaymentService  // ⭐ NEW: For fee calculation
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
        // ⭐ NEW: Calculate local date from timezone
        val now = Instant.now()
        val userZone = ZoneId.of(req.timezone)  // ⭐ NEW
        val localDate = now.atZone(userZone).toLocalDate()  // ⭐ NEW
        
        // ⭐ NEW: Calculate fee breakdown to get net amount
        val feeBreakdown = stripeService.calculateFeeBreakdown(v.amount)
        
        val tip = tips.save(
            Tip(
                sender = sender,
                receiver = receiver,
                amount = v.amount,                      // Gross amount
                netAmount = feeBreakdown.receiverGets,  // ⭐ NEW: Net amount
                totalFees = feeBreakdown.totalFee,      // ⭐ NEW: Total fees
                platformFee = feeBreakdown.platformFee, // ⭐ NEW: Platform fee
                createdAt = now,
                createdAtLocal = localDate,  // ⭐ NEW
                timezone = req.timezone,     // ⭐ NEW
                nonce = v.nonce,
                timestamp = v.timestamp,
                verified = true
            )
        )
        
        // ⭐ UPDATED: Send net amount in CENTS to notification
        val amountCents = (feeBreakdown.receiverGets * java.math.BigDecimal(100)).toLong()
        notificationService.notifyTipReceived(
            receiverId = receiver.id!!,
            senderId = sender.id!!,
            senderName = sender.displayName,
            amountCents = amountCents,  // ⭐ CHANGED: Pass cents, not dollars
            tipId = tip.id!!
        )
        
        TipResult("CONFIRMED")
    } catch (e: DataIntegrityViolationException) {
        // DB uniqueness race on (sender_id, nonce)
        TipResult("DUPLICATE")
    }
}

    @GetMapping("/received")
fun getReceivedTips(
    @RequestParam(required = false) date: String?
): List<TipSummaryDto> {
    val receiverId: UUID = authUserId()
    
    // ⭐ SIMPLIFIED: Just parse the date, no timezone conversion!
    val targetDate: LocalDate = date?.let { LocalDate.parse(it) } ?: LocalDate.now()
    
    // ⭐ NEW: Use fast local date query (10-200x faster!)
    val receivedTips: List<Tip> = tips.findByReceiver_IdAndCreatedAtLocal(
        receiverId, 
        targetDate
    )
    
    return receivedTips.map { tip: Tip ->
        TipSummaryDto(
            id = tip.id.toString(),
            senderId = tip.sender?.id?.toString() ?: "",
            senderName = tip.sender?.displayName ?: "Unknown",
            amount = tip.amount,
            netAmount = tip.netAmount,           // ⭐ NEW: Include net amount
            totalFees = tip.totalFees,           // ⭐ NEW: Include total fees
            platformFee = tip.platformFee,       // ⭐ NEW: Include platform fee
            createdAt = tip.createdAt.toString(),
            createdAtLocal = tip.createdAtLocal.toString(),  // ⭐ NEW
            timezone = tip.timezone                          // ⭐ NEW
        )
    }
}

    @GetMapping("/sent")
fun getSentTips(
    @RequestParam(required = false) startDate: String?,
    @RequestParam(required = false) endDate: String?
): List<SentTipSummaryDto> {
    val senderId: UUID = authUserId()
    
    // ⭐ SIMPLIFIED: Just parse dates, no timezone conversion!
    val start: LocalDate = startDate?.let { LocalDate.parse(it) } 
        ?: LocalDate.now().withDayOfMonth(1)
    val end: LocalDate = endDate?.let { LocalDate.parse(it) } 
        ?: LocalDate.now()
    
    // ⭐ NEW: Use fast local date query (10-200x faster!)
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
            netAmount = tip.netAmount,           // ⭐ FIXED: Now includes net amount
            totalFees = tip.totalFees,           // ⭐ FIXED: Now includes total fees
            platformFee = tip.platformFee,       // ⭐ FIXED: Now includes platform fee
            createdAt = tip.createdAt.toString(),
            createdAtLocal = tip.createdAtLocal.toString(),  // ⭐ NEW
            timezone = tip.timezone                          // ⭐ NEW
        )
    }
}
}

data class TipSummaryDto(
    val id: String,
    val senderId: String,
    val senderName: String,
    val amount: BigDecimal,              // Gross amount (what sender paid)
    val netAmount: BigDecimal? = null,   // ⭐ NEW: Net amount (what receiver gets)
    val totalFees: BigDecimal? = null,   // ⭐ NEW: Total fees deducted
    val platformFee: BigDecimal? = null, // ⭐ NEW: Platform fee (your revenue)
    val createdAt: String,
    val createdAtLocal: String,  // ⭐ NEW: "2025-12-29"
    val timezone: String          // ⭐ NEW: "America/Los_Angeles"
)

data class SentTipSummaryDto(
    val id: String,
    val recipientId: String,
    val recipientName: String,
    val amount: BigDecimal,
    val netAmount: BigDecimal? = null,   // ⭐ FIXED: Added field
    val totalFees: BigDecimal? = null,   // ⭐ FIXED: Added field  
    val platformFee: BigDecimal? = null, // ⭐ FIXED: Added field
    val createdAt: String,
    val createdAtLocal: String,  // ⭐ NEW: "2025-12-29"
    val timezone: String          // ⭐ NEW: "America/Los_Angeles"
)