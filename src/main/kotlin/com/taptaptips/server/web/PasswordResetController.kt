package com.taptaptips.server.web

import com.taptaptips.server.service.PasswordResetService
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/auth/password")
class PasswordResetController(
    private val passwordResetService: PasswordResetService
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    
    data class ForgotPasswordRequest(val email: String)
    data class ResetPasswordRequest(val token: String, val newPassword: String)
    data class ValidateTokenRequest(val token: String)
    
    @PostMapping("/forgot")
    fun forgotPassword(@RequestBody request: ForgotPasswordRequest): ResponseEntity<Any> {
        logger.info("🔐 Password reset requested for: ${request.email}")
        
        if (!isValidEmail(request.email)) {
            return ResponseEntity.badRequest().body(mapOf("error" to "Invalid email format"))
        }
        
        try {
            passwordResetService.initiatePasswordReset(request.email)
            
            return ResponseEntity.ok(mapOf(
                "message" to "If an account exists with this email, a password reset link has been sent.",
                "success" to true
            ))
        } catch (e: Exception) {
            logger.error("❌ Error processing password reset request", e)
            return ResponseEntity.status(500).body(mapOf("error" to "Failed to process password reset request"))
        }
    }
    
    @PostMapping("/validate-token")
    fun validateToken(@RequestBody request: ValidateTokenRequest): ResponseEntity<Any> {
        logger.info("🔍 Validating reset token: ${request.token.take(8)}...")
        
        val resetToken = passwordResetService.validateToken(request.token)
        
        return if (resetToken != null) {
            ResponseEntity.ok(mapOf(
                "valid" to true,
                "email" to resetToken.user.email,
                "displayName" to resetToken.user.displayName
            ))
        } else {
            ResponseEntity.status(400).body(mapOf(
                "valid" to false,
                "error" to "Invalid or expired reset token"
            ))
        }
    }
    
    @PostMapping("/reset")
    fun resetPassword(@RequestBody request: ResetPasswordRequest): ResponseEntity<Any> {
        logger.info("🔐 Password reset attempt with token: ${request.token.take(8)}...")
        
        if (request.newPassword.length < 8) {
            return ResponseEntity.badRequest().body(mapOf("error" to "Password must be at least 8 characters long"))
        }
        
        try {
            val success = passwordResetService.resetPassword(request.token, request.newPassword)
            
            return if (success) {
                logger.info("✅ Password reset successful")
                ResponseEntity.ok(mapOf(
                    "success" to true,
                    "message" to "Password reset successfully. You can now login with your new password."
                ))
            } else {
                logger.warn("⚠️ Password reset failed - invalid token")
                ResponseEntity.status(400).body(mapOf(
                    "success" to false,
                    "error" to "Invalid or expired reset token"
                ))
            }
        } catch (e: Exception) {
            logger.error("❌ Error resetting password", e)
            return ResponseEntity.status(500).body(mapOf("error" to "Failed to reset password"))
        }
    }
    
    private fun isValidEmail(email: String): Boolean {
        val emailRegex = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$".toRegex()
        return email.matches(emailRegex)
    }
}