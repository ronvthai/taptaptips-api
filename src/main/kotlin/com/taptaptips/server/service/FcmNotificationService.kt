package com.taptaptips.server.service

import com.google.firebase.messaging.*
import com.taptaptips.server.repo.FcmTokenRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/**
 * Firebase Cloud Messaging service for push notifications.
 * Works for both Android and iOS devices.
 */
@Service
class FcmNotificationService(
    private val fcmTokenRepository: FcmTokenRepository
) {
    private val logger = LoggerFactory.getLogger(FcmNotificationService::class.java)
    
    /**
     * Send tip notification via FCM to all user's active devices.
     * Returns true if at least one notification was sent successfully.
     */
    fun sendTipNotification(
        receiverId: UUID,
        senderId: UUID,
        senderName: String,
        amount: Int,
        tipId: UUID
    ): Boolean {
        val tokens = fcmTokenRepository.findByUser_IdAndIsActiveTrue(receiverId)
        
        if (tokens.isEmpty()) {
            logger.info("📭 No FCM tokens for user $receiverId")
            return false
        }
        
        logger.info("📤 Sending FCM notification to ${tokens.size} device(s) for user $receiverId")
        
        var successCount = 0
        var failureCount = 0
        
        tokens.forEach { fcmToken ->
            try {
                // Build notification message
                val message = Message.builder()
                    .setToken(fcmToken.token)
                    .setNotification(
                        Notification.builder()
                            .setTitle("💰 Tip Received!")
                            .setBody("$$amount from $senderName")
                            .build()
                    )
                    // Data payload for app to handle
                    .putData("type", "tip_received")
                    .putData("tipId", tipId.toString())
                    .putData("senderId", senderId.toString())
                    .putData("senderName", senderName)
                    .putData("amount", amount.toString())
                    .putData("timestamp", System.currentTimeMillis().toString())
                    // Android-specific config
                    .setAndroidConfig(
                        AndroidConfig.builder()
                            .setPriority(AndroidConfig.Priority.HIGH)
                            .setNotification(
                                AndroidNotification.builder()
                                    .setSound("default")
                                    .setChannelId("tip_notifications")
                                    .build()
                            )
                            .build()
                    )
                    // iOS-specific config
                    .setApnsConfig(
                        ApnsConfig.builder()
                            .setAps(
                                Aps.builder()
                                    .setSound("default")
                                    .setBadge(1)
                                    .build()
                            )
                            .build()
                    )
                    .build()
                
                // Send via FCM
                val response = FirebaseMessaging.getInstance().send(message)
                
                // Update last used timestamp
                fcmToken.lastUsed = Instant.now()
                fcmTokenRepository.save(fcmToken)
                
                successCount++
                logger.info("✅ FCM sent to ${fcmToken.platform} device: $response")
                
            } catch (e: FirebaseMessagingException) {
                failureCount++
                
                // Handle token errors
                when (e.messagingErrorCode) {
                    MessagingErrorCode.INVALID_ARGUMENT,
                    MessagingErrorCode.UNREGISTERED -> {
                        // Token is invalid or device uninstalled app
                        logger.warn("⚠️ Invalid FCM token, deactivating: ${e.message}")
                        fcmTokenRepository.deactivateToken(fcmToken.token)
                    }
                    else -> {
                        logger.error("❌ FCM error for token ${fcmToken.id}: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                failureCount++
                logger.error("❌ Unexpected error sending FCM", e)
            }
        }
        
        logger.info("📊 FCM results: $successCount success, $failureCount failed")
        return successCount > 0
    }
    
    /**
     * Send a test notification (useful for debugging)
     */
    fun sendTestNotification(userId: UUID, message: String): Boolean {
        val tokens = fcmTokenRepository.findByUser_IdAndIsActiveTrue(userId)
        
        if (tokens.isEmpty()) {
            logger.warn("No FCM tokens for user $userId")
            return false
        }
        
        return try {
            val fcmMessage = Message.builder()
                .setToken(tokens.first().token)
                .setNotification(
                    Notification.builder()
                        .setTitle("Test Notification")
                        .setBody(message)
                        .build()
                )
                .build()
            
            FirebaseMessaging.getInstance().send(fcmMessage)
            logger.info("✅ Test notification sent")
            true
        } catch (e: Exception) {
            logger.error("❌ Failed to send test notification", e)
            false
        }
    }
}
