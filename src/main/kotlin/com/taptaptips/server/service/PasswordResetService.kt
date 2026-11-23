package com.taptaptips.server.service

import com.taptaptips.server.domain.AppUser
import com.taptaptips.server.domain.PasswordResetToken
import com.taptaptips.server.repo.AppUserRepository
import com.taptaptips.server.repo.PasswordResetTokenRepository
import com.taptaptips.server.security.RefreshTokenService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit

@Service
class PasswordResetService(
    private val userRepo: AppUserRepository,
    private val tokenRepo: PasswordResetTokenRepository,
    private val emailService: EmailService,
    private val passwordEncoder: PasswordEncoder,
    private val refreshTokens: RefreshTokenService
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    
    companion object {
        const val TOKEN_EXPIRY_HOURS = 1L
    }
    
    /**
     * Initiate password reset process
     */
    @Transactional
    fun initiatePasswordReset(email: String): Boolean {
        val user = userRepo.findByEmail(email)
        
        if (user == null) {
            logger.warn("⚠️ Password reset requested for non-existent email: $email")
            // For security, don't reveal that email doesn't exist
            return true
        }
        
        // Invalidate all previous tokens for this user
        tokenRepo.invalidateAllUserTokens(user)
        
        // Create new token
        val expiresAt = Instant.now().plus(TOKEN_EXPIRY_HOURS, ChronoUnit.HOURS)
        val token = PasswordResetToken(
            user = user,
            expiresAt = expiresAt
        )
        
        tokenRepo.save(token)
        logger.info("✅ Created password reset token for user ${user.id}, expires at $expiresAt")
        
        // Send email
        try {
            emailService.sendPasswordResetEmail(
                toEmail = user.email,
                resetToken = token.token,
                displayName = user.displayName
            )
            logger.info("✅ Password reset email sent to ${user.email}")
        } catch (e: Exception) {
            logger.error("❌ Failed to send password reset email", e)
            throw RuntimeException("Failed to send reset email", e)
        }
        
        return true
    }
    
    /**
     * Validate a reset token
     */
    fun validateToken(token: String): PasswordResetToken? {
        val resetToken = tokenRepo.findByToken(token)
        
        if (resetToken == null) {
            logger.warn("⚠️ Invalid reset token: $token")
            return null
        }
        
        if (!resetToken.isValid()) {
            logger.warn("⚠️ Expired or used reset token: $token")
            return null
        }
        
        return resetToken
    }
    
    /**
     * Reset password using token
     */
    @Transactional
    fun resetPassword(token: String, newPassword: String): Boolean {
        val resetToken = validateToken(token)
        
        if (resetToken == null) {
            logger.warn("⚠️ Failed to reset password - invalid token")
            return false
        }
        
        val user = resetToken.user
        
        // Update password
        user.passwordHash = passwordEncoder.encode(newPassword)
        user.updatedAt = Instant.now()
        userRepo.save(user)
        
        // Mark token as used
        resetToken.used = true
        resetToken.usedAt = Instant.now()
        tokenRepo.save(resetToken)

        // New password → every signed-in device has to sign in again with it.
        val revoked = refreshTokens.revokeAllForUser(user.id)

        logger.info("✅ Password reset successful for user ${user.id} (signed out $revoked device session(s))")
        
        return true
    }
    
    /**
     * Cleanup expired and used tokens (runs every hour)
     */
    @Scheduled(fixedRate = 3600000) // 1 hour in milliseconds
    @Transactional
    fun cleanupExpiredTokens() {
        val deleted = tokenRepo.deleteExpiredAndUsedTokens()
        if (deleted > 0) {
            logger.info("🧹 Cleaned up $deleted expired/used password reset tokens")
        }
    }
}