package com.taptaptips.server.web

import com.taptaptips.server.repo.AppUserRepository
import com.taptaptips.server.repo.FcmTokenRepository
import org.slf4j.LoggerFactory
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
    private val logger = LoggerFactory.getLogger(FcmController::class.java)

    private fun authUserId(): UUID =
        UUID.fromString(SecurityContextHolder.getContext().authentication.name)

    /**
     * Register or update FCM token for the current user.
     * Called when the app starts or when FCM token is refreshed.
     *
     * IMPORTANT: when the same FCM token is re-registered under a different
     * user (i.e. account switch on the same physical device), this re-points
     * the row at the new user.  Without this, notifications for the OLD
     * user would keep arriving on the device that's now logged in as the
     * NEW user.
     */
    @PostMapping("/register")
    @org.springframework.transaction.annotation.Transactional
    fun registerToken(@RequestBody request: RegisterFcmTokenRequest): ResponseEntity<Any> {
        val userId = authUserId()
        userRepository.findById(userId).orElseThrow()

        // Atomic upsert (see FcmTokenRepository.upsertToken) — replaces the
        // find-then-save pattern that raced under concurrent requests for
        // the same token and threw DataIntegrityViolationException on the
        // idx_fcm_token unique constraint. Whichever user_id wins the last
        // write is authoritative — same account-switch behavior as before,
        // just race-proof now.
        fcmTokenRepository.upsertToken(
            id = UUID.randomUUID(),
            userId = userId,
            token = request.token,
            platform = request.platform,
            deviceInfo = request.deviceInfo,
            now = Instant.now()
        )
        logger.info("📱 FCM token upserted for user=$userId platform=${request.platform}")

        return ResponseEntity.ok(mapOf("status" to "registered"))
    }

    /**
     * Delete FCM token (called when user logs out or uninstalls app).
     *
     * The client should call this BEFORE clearing the JWT, so the request
     * is still authenticated and the cleanup happens cleanly.
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
