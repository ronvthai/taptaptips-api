package com.taptaptips.server.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Hybrid notification service that intelligently chooses between SSE and FCM.
 * 
 * Strategy:
 * - If user has active SSE connection → Use SSE (instant, real-time)
 * - If no SSE connection → Use FCM (reliable, works when app closed)
 * - This provides the best user experience for all scenarios
 */
@Service
class HybridNotificationService(
    private val sseNotificationService: SseNotificationService,
    private val fcmNotificationService: FcmNotificationService
) {
    private val logger = LoggerFactory.getLogger(HybridNotificationService::class.java)
    
    /**
     * Send tip notification using the best available method.
     * Tries SSE first (instant), falls back to FCM if needed.
     */
    fun notifyTipReceived(
        receiverId: UUID,
        senderId: UUID,
        senderName: String,
        amount: Int,
        tipId: UUID
    ) {
        logger.info("📬 Notifying user $receiverId about tip from $senderName")
        
        // Try SSE first (instant delivery if app is open)
        val sseSuccess = try {
            sseNotificationService.notifyTipReceived(
                receiverId = receiverId,
                senderId = senderId,
                senderName = senderName,
                amount = amount,
                tipId = tipId
            )
            // Check if there were active connections
            sseNotificationService.hasActiveConnection(receiverId)
        } catch (e: Exception) {
            logger.error("SSE notification failed", e)
            false
        }
        
        if (sseSuccess) {
            logger.info("✅ Delivered via SSE (app is open)")
        } else {
            logger.info("📱 No SSE connection, trying FCM...")
            
            // Fall back to FCM (works when app is closed)
            val fcmSuccess = try {
                fcmNotificationService.sendTipNotification(
                    receiverId = receiverId,
                    senderId = senderId,
                    senderName = senderName,
                    amount = amount,
                    tipId = tipId
                )
            } catch (e: Exception) {
                logger.error("FCM notification failed", e)
                false
            }
            
            if (fcmSuccess) {
                logger.info("✅ Delivered via FCM (push notification)")
            } else {
                logger.warn("⚠️ All notification methods failed for user $receiverId")
            }
        }
    }
}
