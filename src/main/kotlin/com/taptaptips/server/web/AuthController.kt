package com.taptaptips.server.web

import com.taptaptips.server.domain.AppUser
import com.taptaptips.server.repo.AppUserRepository
import com.taptaptips.server.security.JwtService
import com.taptaptips.server.service.StripePaymentService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/auth")
class AuthController(
    private val userRepo: AppUserRepository,
    private val jwt: JwtService,
    private val stripeService: StripePaymentService
) {
    private val pwd: PasswordEncoder = BCryptPasswordEncoder()
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        // Shared limits — keep in sync with iOS PasswordPolicy.swift
        const val MAX_EMAIL_LENGTH        = 254
        const val MAX_DISPLAY_NAME_LENGTH = 100
        const val MIN_PASSWORD_LENGTH     = 8
        const val MAX_PASSWORD_LENGTH     = 128  // prevents bcrypt DoS on very long inputs
        const val MAX_AVATAR_BYTES        = 2_000_000
    }

    data class LoginReq(val email: String, val password: String)

    data class ErrorResponse(
        val error: String,
        val message: String? = null,
        val timestamp: Long = System.currentTimeMillis()
    )

    @PostMapping("/register", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun register(
        @RequestParam email: String,
        @RequestParam password: String,
        @RequestParam displayName: String,
        @RequestParam(defaultValue = "false") wantsToReceiveTips: Boolean,
        @RequestParam("avatar") avatar: MultipartFile
    ): ResponseEntity<Any> {
        logger.info("📝 Registration: email=$email, wantsToReceiveTips=$wantsToReceiveTips")

        // ── Input length limits ──────────────────────────────────────────────
        if (email.length > MAX_EMAIL_LENGTH) {
            return ResponseEntity.badRequest().body(ErrorResponse(
                error = "Email too long",
                message = "Email must be at most $MAX_EMAIL_LENGTH characters"
            ))
        }
        if (displayName.isBlank()) {
            return ResponseEntity.badRequest().body(ErrorResponse(
                error = "Display name required",
                message = "Please enter a display name"
            ))
        }
        if (displayName.length > MAX_DISPLAY_NAME_LENGTH) {
            return ResponseEntity.badRequest().body(ErrorResponse(
                error = "Display name too long",
                message = "Display name must be at most $MAX_DISPLAY_NAME_LENGTH characters"
            ))
        }
        if (password.length > MAX_PASSWORD_LENGTH) {
            return ResponseEntity.badRequest().body(ErrorResponse(
                error = "Password too long",
                message = "Password must be at most $MAX_PASSWORD_LENGTH characters"
            ))
        }

        // ── Format and strength checks ───────────────────────────────────────
        if (!isValidEmail(email)) {
            logger.warn("❌ Registration failed: Invalid email format - $email")
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse(
                error = "Invalid email format",
                message = "Please enter a valid email address"
            ))
        }

        if (password.length < MIN_PASSWORD_LENGTH) {
            logger.warn("❌ Registration failed: Password too short")
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse(
                error = "Password too short",
                message = "Password must be at least $MIN_PASSWORD_LENGTH characters long"
            ))
        }

        if (userRepo.existsByEmail(email)) {
            logger.warn("❌ Registration failed: Email already exists - $email")
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse(
                error = "Email already registered",
                message = "An account with this email already exists. Please sign in instead."
            ))
        }

        // ── Avatar validation ──────────────────────────────────────────────────
        // Required server-side too — not just a client-side UI rule. This is
        // what closes the gap that previously let accounts get created with
        // no photo: registration used to be POST /auth/register (JSON, no
        // avatar) followed by a *separate* PUT /users/{id}/avatar call. Any
        // failure in that second call — including a mid-upload connection
        // drop — left a fully valid, photo-less account behind. Now there is
        // no second call: the account row is written with its photo already
        // attached, in one request.
        if (avatar.isEmpty) {
            return ResponseEntity.badRequest().body(ErrorResponse(
                error = "Profile photo required",
                message = "Please add a profile photo to create your account"
            ))
        }
        if (avatar.size > MAX_AVATAR_BYTES) {
            return ResponseEntity.badRequest().body(ErrorResponse(
                error = "Photo too large",
                message = "Profile photo must be under ${MAX_AVATAR_BYTES / 1_000_000}MB"
            ))
        }

        return try {
            val u = userRepo.save(AppUser(
                email        = email,
                passwordHash = pwd.encode(password),
                displayName  = displayName.trim(),
                wantsToReceiveTips = wantsToReceiveTips,
                profilePicture = avatar.bytes
            ))

            logger.info("✅ Created user ${u.id} (with avatar, ${avatar.size} bytes)")

            var stripeOnboardingUrl: String? = null
            if (wantsToReceiveTips) {
                try {
                    logger.info("🏦 Creating Stripe account for user ${u.id}...")
                    val stripeAccountId = stripeService.createConnectedAccount(u)
                    stripeOnboardingUrl = stripeService.createAccountLink(stripeAccountId)
                    logger.info("✅ Stripe account created: $stripeAccountId")
                } catch (e: Exception) {
                    logger.error("❌ Failed to create Stripe account", e)
                    // Don't fail registration — user can set up later
                }
            }

            ResponseEntity.ok(mapOf(
                "accessToken"             to jwt.issueAccess(u.id),
                "userId"                  to u.id.toString(),
                "username"                to u.displayName,
                "wantsToReceiveTips"      to u.wantsToReceiveTips,
                "requiresStripeOnboarding" to wantsToReceiveTips,
                "stripeOnboardingUrl"     to stripeOnboardingUrl,
                "isStripeOnboarded"       to false
            ))
        } catch (e: Exception) {
            logger.error("❌ Registration failed with exception", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ErrorResponse(
                error = "Registration failed",
                message = "An unexpected error occurred. Please try again."
            ))
        }
    }

    @PostMapping("/login")
    fun login(@RequestBody r: LoginReq): ResponseEntity<Any> {
        logger.info("🔐 Login attempt: email=${r.email}")

        // ── Reject obviously malformed inputs before hitting the DB ──────────
        if (r.email.length > MAX_EMAIL_LENGTH || r.password.length > MAX_PASSWORD_LENGTH) {
            // Return generic 401 — don't hint at why (timing-safe response)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ErrorResponse(
                error = "Invalid credentials",
                message = "Incorrect email or password. Please try again."
            ))
        }

        if (!isValidEmail(r.email)) {
            logger.warn("❌ Login failed: Invalid email format")
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse(
                error = "Invalid email",
                message = "Please enter a valid email address"
            ))
        }

        val u = userRepo.findByEmail(r.email)

        if (u == null) {
            logger.warn("❌ Login failed: User not found - ${r.email}")
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ErrorResponse(
                error = "Invalid credentials",
                message = "Incorrect email or password. Please try again."
            ))
        }

        if (!pwd.matches(r.password, u.passwordHash)) {
            logger.warn("❌ Login failed: Invalid password for ${r.email}")
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ErrorResponse(
                error = "Invalid credentials",
                message = "Incorrect email or password. Please try again."
            ))
        }

        logger.info("✅ User ${u.id} logged in successfully")

        return try {
            var needsOnboarding = false
            var onboardingUrl: String? = null

            if (u.wantsToReceiveTips && !u.stripeOnboarded) {
                needsOnboarding = true
                if (u.stripeAccountId != null) {
                    try {
                        onboardingUrl = stripeService.createAccountLink(u.stripeAccountId!!)
                        logger.info("✅ Generated onboarding URL for user ${u.id}")
                    } catch (e: Exception) {
                        logger.error("❌ Failed to create onboarding link", e)
                    }
                }
            }

            ResponseEntity.ok(mapOf(
                "accessToken"              to jwt.issueAccess(u.id),
                "userId"                   to u.id.toString(),
                "username"                 to u.displayName,
                "wantsToReceiveTips"       to u.wantsToReceiveTips,
                "isStripeOnboarded"        to u.stripeOnboarded,
                "requiresStripeOnboarding" to needsOnboarding,
                "stripeOnboardingUrl"      to onboardingUrl
            ))
        } catch (e: Exception) {
            logger.error("❌ Login successful but post-login operations failed", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ErrorResponse(
                error = "Login error",
                message = "An unexpected error occurred. Please try again."
            ))
        }
    }

    private fun isValidEmail(email: String): Boolean {
        val emailRegex = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$".toRegex()
        return email.matches(emailRegex)
    }
}
