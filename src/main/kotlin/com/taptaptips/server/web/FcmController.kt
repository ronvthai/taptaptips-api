package com.taptaptips.server.web

import com.taptaptips.server.domain.FcmToken
import com.taptaptips.server.repo.AppUserRepository
import com.taptaptips.server.repo.FcmTokenRepository
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/fcm")
class FcmController(
    private val fcmTokenRepository: FcmTokenRepository,
    private val userRepository: AppUserRepository
) {
    
    private fun authUserId(): UUID =
        UUID.fromString(SecurityContextHolder.getContext().authentication.name)
    
    /**
     * Register or update FCM token for the current user.
     * Called when the app starts or when FCM token is refreshed.
     */
    @PostMapping("/register")
    fun registerToken(@RequestBody request: RegisterFcmTokenRequest): ResponseEntity<Any> {
        val userId = authUserId()
        val user = userRepository.findById(userId).orElseThrow()
        
        // Check if token already exists
        val existing = fcmTokenRepository.findByToken(request.token)
        
        if (existing != null) {
            // Update existing token
            existing.platform = request.platform
            existing.deviceInfo = request.deviceInfo
            existing.updatedAt = Instant.now()
            existing.isActive = true
            fcmTokenRepository.save(existing)
            
            return ResponseEntity.ok(mapOf(
                "status" to "updated",
                "tokenId" to existing.id.toString()
            ))
        } else {
            // Create new token
            val newToken = FcmToken(
                user = user,
                token = request.token,
                platform = request.platform,
                deviceInfo = request.deviceInfo
            )
            fcmTokenRepository.save(newToken)
            
            return ResponseEntity.ok(mapOf(
                "status" to "registered",
                "tokenId" to newToken.id.toString()
            ))
        }
    }
    
    /**
     * Delete FCM token (called when user logs out or uninstalls app)
     */
    @DeleteMapping("/token")
    fun deleteToken(@RequestParam token: String): ResponseEntity<Any> {
        val fcmToken = fcmTokenRepository.findByToken(token)
        
        return if (fcmToken != null) {
            fcmTokenRepository.delete(fcmToken)
            ResponseEntity.ok(mapOf("status" to "deleted"))
        } else {
            ResponseEntity.ok(mapOf("status" to "not_found"))
        }
    }
    
    /**
     * Get all registered tokens for current user (debugging)
     */
    @GetMapping("/tokens")
    fun getMyTokens(): ResponseEntity<Any> {
        val userId = authUserId()
        val tokens = fcmTokenRepository.findByUser_IdAndIsActiveTrue(userId)
        
        return ResponseEntity.ok(mapOf(
            "count" to tokens.size,
            "tokens" to tokens.map { 
                mapOf(
                    "platform" to it.platform,
                    "deviceInfo" to it.deviceInfo,
                    "createdAt" to it.createdAt.toString(),
                    "lastUsed" to it.lastUsed?.toString()
                )
            }
        ))
    }
}

data class RegisterFcmTokenRequest(
    val token: String,
    val platform: String,  // "android" or "ios"
    val deviceInfo: String? = null
)
