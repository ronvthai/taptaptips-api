package com.taptaptips.server.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Notification service using Firebase Cloud Messaging (FCM) only.
 * 
 * Provides reliable push notifications for:
 * - App in foreground (shows notification + triggers in-app animation)
 * - App in background (shows notification)
 * - App closed (shows notification, opens app on tap)
 * 
 * Benefits over SSE:
 * - Works when app is closed
 * - Survives network changes
 * - Battery efficient (system-managed connection)
 * - Automatic retry and offline queuing
 * - 99.9% delivery rate via Google's infrastructure
 */
@Service
class NotificationService(
    private val fcmNotificationService: FcmNotificationService
) {
    private val logger = LoggerFactory.getLogger(NotificationService::class.java)
    
    /**
     * Send tip notification via FCM.
     * Works whether app is open, backgrounded, or closed.
     */
    fun notifyTipReceived(
        receiverId: UUID,
        senderId: UUID,
        senderName: String,
        amount: Int,
        tipId: UUID
    ): Boolean {
        logger.info("📬 Notifying user $receiverId about $$amount tip from $senderName")
        
        val success = try {
            fcmNotificationService.sendTipNotification(
                receiverId = receiverId,
                senderId = senderId,
                senderName = senderName,
                amount = amount,
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
     * Useful for debugging and onboarding flow.
     */
    fun sendTestNotification(userId: UUID, message: String): Boolean {
        logger.info("🧪 Sending test notification to user $userId")
        return fcmNotificationService.sendTestNotification(userId, message)
    }
}