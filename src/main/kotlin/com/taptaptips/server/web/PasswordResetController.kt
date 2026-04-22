package com.taptaptips.server.web

import com.taptaptips.server.service.PasswordResetService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
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

    data class ErrorResponse(
        val error: String,
        val message: String? = null,
        val timestamp: Long = System.currentTimeMillis()
    )

    @PostMapping("/forgot")
    fun forgotPassword(@RequestBody request: ForgotPasswordRequest): ResponseEntity<Any> {
        logger.info("🔐 Password reset requested for: ${request.email}")

        // Validate email format
        if (!isValidEmail(request.email)) {
            logger.warn("❌ Password reset failed: Invalid email format - ${request.email}")
            return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse(
                    error = "Invalid email format",
                    message = "Please enter a valid email address"
                ))
        }

        try {
            // Initiate password reset
            // Note: We don't reveal whether the email exists or not for security
            passwordResetService.initiatePasswordReset(request.email)

            logger.info("✅ Password reset email sent (if account exists)")

            return ResponseEntity.ok(mapOf(
                "message" to "If an account exists with this email, a password reset link has been sent.",
                "success" to true,
                "email" to request.email
            ))
        } catch (e: Exception) {
            logger.error("❌ Error processing password reset request", e)
            return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse(
                    error = "Server error",
                    message = "Failed to process password reset request. Please try again."
                ))
        }
    }

    @PostMapping("/validate-token")
    fun validateToken(@RequestBody request: ValidateTokenRequest): ResponseEntity<Any> {
        logger.info("🔍 Validating reset token: ${request.token.take(8)}...")

        // Validate token format
        if (request.token.isBlank() || request.token.length < 32) {
            logger.warn("❌ Token validation failed: Invalid token format")
            return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(mapOf(
                    "valid" to false,
                    "error" to "Invalid reset link format. Please request a new one."
                ))
        }

        try {
            val resetToken = passwordResetService.validateToken(request.token)

            return if (resetToken != null) {
                logger.info("✅ Token validated successfully for user: ${resetToken.user.email}")
                ResponseEntity.ok(mapOf(
                    "valid" to true,
                    "email" to resetToken.user.email,
                    "displayName" to resetToken.user.displayName
                ))
            } else {
                logger.warn("⚠️ Token validation failed: Token not found or expired")
                ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(mapOf(
                        "valid" to false,
                        "error" to "This reset link is invalid or has expired. Please request a new one."
                    ))
            }
        } catch (e: Exception) {
            logger.error("❌ Error validating token", e)
            return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(mapOf(
                    "valid" to false,
                    "error" to "Unable to validate reset link. Please try again."
                ))
        }
    }

    /**
     * Reset the user's password using a valid reset token.
     *
     * Status codes:
     *   200 → password updated
     *   400 → invalid or expired reset token (link problem)
     *   422 → new password failed validation (user needs to pick a different one)
     *   500 → unexpected server error
     *
     * 422 is used for validation failures (too short, too common) so clients
     * can distinguish "the link is dead, start over" from "try a stronger
     * password on this same screen."
     */
    @PostMapping("/reset")
    fun resetPassword(@RequestBody request: ResetPasswordRequest): ResponseEntity<Any> {
        logger.info("🔐 Password reset attempt with token: ${request.token.take(8)}...")

        // Length check
        if (request.newPassword.length < 8) {
            logger.warn("❌ Password reset failed: Password too short")
            return ResponseEntity
                .status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(mapOf(
                    "success" to false,
                    "error" to "Password must be at least 8 characters long."
                ))
        }

        // Common-password check
        val weakPasswords = setOf(
            "password", "12345678", "qwerty123", "password123"
        )
        if (request.newPassword.lowercase() in weakPasswords) {
            logger.warn("❌ Password reset failed: Weak password detected")
            return ResponseEntity
                .status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(mapOf(
                    "success" to false,
                    "error" to "That password is too common. Please choose something harder to guess."
                ))
        }

        try {
            val success = passwordResetService.resetPassword(request.token, request.newPassword)

            return if (success) {
                logger.info("✅ Password reset successful")
                ResponseEntity.ok(mapOf(
                    "success" to true,
                    "message" to "Password reset successfully. You can now sign in with your new password."
                ))
            } else {
                logger.warn("⚠️ Password reset failed: Invalid or expired token")
                ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(mapOf(
                        "success" to false,
                        "error" to "Invalid or expired reset link. Please request a new one."
                    ))
            }
        } catch (e: IllegalArgumentException) {
            logger.warn("⚠️ Password reset failed: ${e.message}")
            return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(mapOf(
                    "success" to false,
                    "error" to (e.message ?: "Invalid reset request")
                ))
        } catch (e: Exception) {
            logger.error("❌ Error resetting password", e)
            return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(mapOf(
                    "success" to false,
                    "error" to "Failed to reset password. Please try again."
                ))
        }
    }

    /**
     * Validate email format using regex
     */
    private fun isValidEmail(email: String): Boolean {
        val emailRegex = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$".toRegex()
        return email.matches(emailRegex)
    }
}
