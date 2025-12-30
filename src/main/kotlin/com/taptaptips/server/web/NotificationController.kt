package com.taptaptips.server.web

import com.taptaptips.server.repo.FcmTokenRepository
import com.taptaptips.server.service.NotificationService
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.*
import java.util.UUID

/**
 * Notification endpoints for FCM-based push notifications.
 * 
 * Endpoints:
 * - GET /notifications/status - Check user's registered FCM devices
 * - POST /notifications/test - Send test notification (useful for debugging)
 */
@RestController
@RequestMapping("/notifications")
class NotificationController(
    private val fcmTokenRepository: FcmTokenRepository,
    private val notificationService: NotificationService
) {
    
    /**
     * Get user's notification status.
     * Shows all registered FCM devices and their details.
     * 
     * Response:
     * {
     *   "userId": "...",
     *   "fcmDevices": 2,
     *   "devices": [
     *     {
     *       "platform": "android",
     *       "deviceInfo": "Pixel 6 (Android 14)",
     *       "lastUsed": "2025-01-15T10:30:00Z"
     *     }
     *   ]
     * }
     */
    @GetMapping("/status")
    fun getStatus(): Map<String, Any> {
        val userId = UUID.fromString(
            SecurityContextHolder.getContext().authentication.name
        )
        
        val tokens = fcmTokenRepository.findByUser_IdAndIsActiveTrue(userId)
        
        return mapOf(
            "userId" to userId.toString(),
            "fcmDevices" to tokens.size,
            "devices" to tokens.map { 
                mapOf(
                    "platform" to it.platform,
                    "deviceInfo" to it.deviceInfo,
                    "lastUsed" to it.lastUsed?.toString(),
                    "createdAt" to it.createdAt.toString()
                )
            },
            "timestamp" to System.currentTimeMillis()
        )
    }
    
    /**
     * Send a test notification to verify FCM is working.
     * Useful for debugging and user onboarding.
     * 
     * Request body:
     * {
     *   "message": "Test notification message"
     * }
     */
    @PostMapping("/test")
    fun sendTestNotification(@RequestBody request: Map<String, String>): Map<String, Any> {
        val userId = UUID.fromString(
            SecurityContextHolder.getContext().authentication.name
        )
        
        val message = request["message"] ?: "This is a test notification"
        val success = notificationService.sendTestNotification(userId, message)
        
        return mapOf(
            "success" to success,
            "message" to if (success) {
                "Test notification sent successfully"
            } else {
                "Failed to send test notification (no active FCM tokens)"
            }
        )
    }
}