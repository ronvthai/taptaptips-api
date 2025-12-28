package com.taptaptips.server.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Server-Sent Events service for real-time tip notifications.
 * Works on Android, iOS, and Web without Firebase.
 */
@Service
class SseNotificationService {
    private val logger = LoggerFactory.getLogger(SseNotificationService::class.java)
    
    // Track active SSE connections per user
    // CopyOnWriteArrayList for thread-safe iteration during concurrent modifications
    private val connections = ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEmitter>>()
    
    /**
     * Register a new SSE connection for a user.
     * Called when user opens the app.
     */
    fun registerConnection(userId: UUID): SseEmitter {
        val emitter = SseEmitter(0L) // 0 = no timeout (keep alive)
        
        // Add to connections
        connections.computeIfAbsent(userId) { CopyOnWriteArrayList() }.add(emitter)
        
        logger.info("📱 SSE connection registered for user $userId (total: ${connections[userId]?.size})")
        
        // Setup cleanup handlers
        emitter.onCompletion {
            removeConnection(userId, emitter)
            logger.info("✅ SSE connection completed for user $userId")
        }
        
        emitter.onTimeout {
            removeConnection(userId, emitter)
            logger.warn("⏱️ SSE connection timeout for user $userId")
        }
        
        emitter.onError { throwable ->
            removeConnection(userId, emitter)
            logger.error("❌ SSE connection error for user $userId", throwable)
        }
        
        // Send initial connection confirmation
        try {
            emitter.send(
                SseEmitter.event()
                    .name("connected")
                    .data(mapOf(
                        "status" to "connected",
                        "userId" to userId.toString(),
                        "timestamp" to System.currentTimeMillis()
                    ))
            )
        } catch (e: Exception) {
            logger.error("Failed to send connection event", e)
            removeConnection(userId, emitter)
        }
        
        return emitter
    }
    
    /**
     * Send tip notification to all of user's active connections.
     * Called after a tip is successfully created.
     * Returns true if there were active connections.
     */
    fun notifyTipReceived(
        receiverId: UUID,
        senderId: UUID,
        senderName: String,
        amount: Int,
        tipId: UUID
    ): Boolean {
        val emitters = connections[receiverId]
        
        if (emitters.isNullOrEmpty()) {
            logger.info("📭 No active SSE connections for user $receiverId")
            return false
        }
        
        logger.info("📤 Sending SSE tip notification to ${emitters.size} connection(s) for user $receiverId")
        
        val event = SseEmitter.event()
            .name("tip_received")
            .data(mapOf(
                "type" to "tip_received",
                "tipId" to tipId.toString(),
                "senderId" to senderId.toString(),
                "senderName" to senderName,
                "amount" to amount,
                "timestamp" to System.currentTimeMillis()
            ))
        
        var successCount = 0
        var failCount = 0
        
        emitters.forEach { emitter ->
            try {
                emitter.send(event)
                successCount++
            } catch (e: Exception) {
                logger.error("Failed to send to one connection", e)
                removeConnection(receiverId, emitter)
                failCount++
            }
        }
        
        logger.info("✅ SSE notification sent: $successCount success, $failCount failed")
        return successCount > 0
    }
    
    /**
     * Check if a user has any active SSE connections.
     * Used by HybridNotificationService to decide between SSE and FCM.
     */
    fun hasActiveConnection(userId: UUID): Boolean {
        val emitters = connections[userId]
        return !emitters.isNullOrEmpty()
    }
    
    /**
     * Remove a connection and clean up.
     */
    private fun removeConnection(userId: UUID, emitter: SseEmitter) {
        connections[userId]?.remove(emitter)
        
        // Clean up empty lists
        if (connections[userId]?.isEmpty() == true) {
            connections.remove(userId)
        }
        
        try {
            emitter.complete()
        } catch (e: Exception) {
            // Already completed or errored
        }
    }
    
    /**
     * Get count of active connections (for monitoring/debugging).
     */
    fun getActiveConnectionCount(): Int {
        return connections.values.sumOf { it.size }
    }
    
    /**
     * Get count of users with active connections.
     */
    fun getActiveUserCount(): Int {
        return connections.size
    }
}
