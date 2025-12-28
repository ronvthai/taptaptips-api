package com.taptaptips.server.web

import com.taptaptips.server.service.SseNotificationService
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.UUID

@RestController
@RequestMapping("/notifications")
class NotificationController(
    private val sseNotificationService: SseNotificationService
) {
    
    /**
     * SSE endpoint - client connects here to receive real-time notifications.
     * Connection stays open and server pushes events as they happen.
     * 
     * Example usage from Android:
     * GET /notifications/stream
     * Headers: Authorization: Bearer <token>
     *          Accept: text/event-stream
     */
    @GetMapping("/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun streamNotifications(): SseEmitter {
        val userId = UUID.fromString(
            SecurityContextHolder.getContext().authentication.name
        )
        
        return sseNotificationService.registerConnection(userId)
    }
    
    /**
     * Health check endpoint to see connection stats.
     * Useful for monitoring and debugging.
     */
    @GetMapping("/status")
    fun getStatus(): Map<String, Any> {
        return mapOf(
            "activeConnections" to sseNotificationService.getActiveConnectionCount(),
            "activeUsers" to sseNotificationService.getActiveUserCount(),
            "timestamp" to System.currentTimeMillis()
        )
    }
}
