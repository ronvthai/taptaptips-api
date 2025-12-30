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
    private val notificationService: NotificationService
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
            val tip = tips.save(
                Tip(
                    sender = sender,
                    receiver = receiver,
                    amount = v.amount,
                    createdAt = Instant.now(),
                    nonce = v.nonce,
                    timestamp = v.timestamp,
                    verified = true
                )
            )
            
            // ⭐ UPDATED: Use hybrid notification service
            // Automatically chooses SSE (if app open) or FCM (if app closed)
            notificationService.notifyTipReceived(
                receiverId = receiver.id!!,
                senderId = sender.id!!,
                senderName = sender.displayName,
                amount = tip.amount.toInt(),
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
        
        // Parse date or default to today
        val targetDate: LocalDate = date?.let { LocalDate.parse(it) } ?: LocalDate.now()
        val zoneId: ZoneId = ZoneId.systemDefault()
        val startOfDay: Instant = targetDate.atStartOfDay(zoneId).toInstant()
        val endOfDay: Instant = targetDate.plusDays(1).atStartOfDay(zoneId).toInstant()
        
        // Fetch tips for the receiver within the date range
        val receivedTips: List<Tip> = tips.findByReceiver_IdAndCreatedAtBetween(
            receiverId, 
            startOfDay, 
            endOfDay
        )
        
        return receivedTips.map { tip: Tip ->
            TipSummaryDto(
                id = tip.id.toString(),
                senderId = tip.sender?.id?.toString() ?: "",
                senderName = tip.sender?.displayName ?: "Unknown",
                amount = tip.amount,
                createdAt = tip.createdAt.toString()
            )
        }
    }

    @GetMapping("/sent")
    fun getSentTips(
        @RequestParam(required = false) startDate: String?,
        @RequestParam(required = false) endDate: String?
    ): List<SentTipSummaryDto> {
        val senderId: UUID = authUserId()
        
        // Parse dates or default to current month
        val start: LocalDate = startDate?.let { LocalDate.parse(it) } 
            ?: LocalDate.now().withDayOfMonth(1)
        val end: LocalDate = endDate?.let { LocalDate.parse(it) } 
            ?: LocalDate.now()
        
        val zoneId: ZoneId = ZoneId.systemDefault()
        val startOfDay: Instant = start.atStartOfDay(zoneId).toInstant()
        val endOfDay: Instant = end.plusDays(1).atStartOfDay(zoneId).toInstant()
        
        // Fetch tips sent by the user within the date range
        val sentTips: List<Tip> = tips.findBySender_IdAndCreatedAtBetween(
            senderId, 
            startOfDay, 
            endOfDay
        )
        
        return sentTips.map { tip: Tip ->
            SentTipSummaryDto(
                id = tip.id.toString(),
                recipientId = tip.receiver?.id?.toString() ?: "",
                recipientName = tip.receiver?.displayName ?: "Unknown",
                amount = tip.amount,
                createdAt = tip.createdAt.toString()
            )
        }
    }
}

data class TipSummaryDto(
    val id: String,
    val senderId: String,
    val senderName: String,
    val amount: BigDecimal,
    val createdAt: String
)

data class SentTipSummaryDto(
    val id: String,
    val recipientId: String,
    val recipientName: String,
    val amount: BigDecimal,
    val createdAt: String
)
