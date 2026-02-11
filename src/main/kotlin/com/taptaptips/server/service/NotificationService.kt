package com.taptaptips.server.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/**
 * Notification service using Firebase Cloud Messaging (FCM) only.
 * 
 * ⭐ UPDATED: Now uses cents (Long) to preserve decimal precision
 */
@Service
class NotificationService(
    private val fcmNotificationService: FcmNotificationService
) {
    private val logger = LoggerFactory.getLogger(NotificationService::class.java)
    
    /**
     * Send tip notification via FCM.
     * 
     * ⭐ UPDATED: Uses amountCents to preserve cents (e.g., 911 for $9.11)
     */
    fun notifyTipReceived(
        receiverId: UUID,
        senderId: UUID,
        senderName: String,
        amountCents: Long,  // ⭐ CHANGED: Was Int amount, now Long amountCents
        tipId: UUID
    ): Boolean {
        val amountDollars = BigDecimal(amountCents).divide(BigDecimal(100), 2, RoundingMode.HALF_UP)
        logger.info("📬 Notifying user $receiverId about $$amountDollars tip from $senderName")
        
        val success = try {
            fcmNotificationService.sendTipNotification(
                receiverId = receiverId,
                senderId = senderId,
                senderName = senderName,
                amountCents = amountCents,  // ⭐ CHANGED: Pass cents
                tipId = tipId
            )
        } catch (e: Exception) {
            logger.error("❌ FCM notification failed for user $receiverId", e)
            false
        }
        
        if (success) {
            logger.info("✅ Notification delivered via FCM to user $receiverId")
        } else {
            logger.warn("⚠️ Notification delivery failed for user $receiverId (no active FCM tokens)")
        }
        
        return success
    }
    
    /**
     * Send test notification to verify FCM is working.
     */
    fun sendTestNotification(userId: UUID, message: String): Boolean {
        logger.info("🧪 Sending test notification to user $userId")
        return fcmNotificationService.sendTestNotification(userId, message)
    }
}