package com.taptaptips.server.web

import com.taptaptips.server.domain.AppUser
import com.taptaptips.server.repo.AppUserRepository
import com.taptaptips.server.security.ClientIp
import com.taptaptips.server.security.JwtService
import com.taptaptips.server.security.RefreshTokenService
import com.taptaptips.server.service.FraudGuardService
import jakarta.servlet.http.HttpServletRequest
import com.taptaptips.server.service.DisplayNameModerator
import com.taptaptips.server.service.RateLimiterService
import com.taptaptips.server.service.StripePaymentService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import com.taptaptips.server.service.ReferralService
import java.util.UUID

@RestController
@RequestMapping("/auth")
class AuthController(
    private val userRepo: AppUserRepository,
    private val jwt: JwtService,
    private val stripeService: StripePaymentService,
    private val rateLimiter: RateLimiterService,
    private val nameModerator: DisplayNameModerator,
    private val referralService: ReferralService,
    private val fraudGuard: FraudGuardService,
    private val clientIp: ClientIp,
    private val refreshTokens: RefreshTokenService
) {
    private val pwd: PasswordEncoder = BCryptPasswordEncoder()

    /** Compared against when the email doesn't exist, so "no such user" takes
     *  as long as "wrong password" and response timing can't reveal which
     *  emails are registered. */
    private val dummyHash: String = pwd.encode("timing-equalizer-not-a-real-password")
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        // Shared limits — keep in sync with iOS PasswordPolicy.swift
        const val MAX_EMAIL_LENGTH        = 254
        const val MAX_DISPLAY_NAME_LENGTH = 100
        const val MIN_PASSWORD_LENGTH     = 8
        const val MAX_PASSWORD_LENGTH     = 128  // prevents bcrypt DoS on very long inputs
        const val MAX_AVATAR_BYTES        = 2_000_000
        const val SUSPENDED_MESSAGE       =
            "This account has been suspended. Contact support@taptaptips.com if you think this is a mistake."
    }

    data class LoginReq(val email: String, val password: String)

    /** Body of POST /auth/token and POST /auth/logout. */
    data class RefreshReq(val refreshToken: String? = null)

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
        @RequestParam("avatar") avatar: MultipartFile,
        @RequestParam(required = false) referralCode: String?,
        request: HttpServletRequest
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
        // ── Word filter ──────────────────────────────────────────────────────
        val nameCheck = nameModerator.check(displayName)
        if (nameCheck is DisplayNameModerator.Result.Blocked) {
            logger.warn("🚫 Registration rejected: display name matched '${nameCheck.term}'")
            return ResponseEntity.badRequest().body(ErrorResponse(
                error = nameModerator.rejectionMessage,
                message = nameModerator.rejectionMessage
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

        // ── Ban list ─────────────────────────────────────────────────────────
        val ip = clientIp.of(request)
        val fraudCheck = fraudGuard.checkSignup(email, ip)
        if (fraudCheck.refuse) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorResponse(
                error = "Registration not allowed",
                message = "We couldn't create an account with these details. Contact support if you think this is a mistake."
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

            logger.info("✅ Created user ${u.id} (with avatar, ${avatar.size} bytes) from $ip")
            fraudGuard.onRegistered(u, ip, fraudCheck)
            // Referral: a bad or expired code never blocks sign-up — it's just ignored.
            val referralError = referralCode?.takeIf { it.isNotBlank() }
                ?.let { referralService.applyCode(u, it) }
            if (referralError != null) {
                logger.info("ℹ️ Referral code not applied for ${u.id}: $referralError")
            }
            if (nameCheck is DisplayNameModerator.Result.Review) {
                nameModerator.flag(u.id, u.displayName, nameCheck.term)
            }

            var stripeOnboardingUrl: String? = null
            // No Stripe account for someone the ban list just suspended.
            if (wantsToReceiveTips && !u.suspended) {
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
                "refreshToken"            to issueRefreshSafely(u.id, request),
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

    /**
     * Sliding session: swap a still-valid access token for a fresh one.
     *
     * The apps call this while the user is actively using them (once a third
     * of the token's lifetime has passed), so an active user never hits the
     * token's hard `exp` and gets kicked out mid-use. An idle user stops
     * refreshing and is logged out by the app's 30-minute inactivity rule;
     * an expired token can't be refreshed (JwtAuthFilter rejects it before
     * we get here), so this never extends a dead session.
     */
    @PostMapping("/refresh")
    fun refresh(
        // Sent by app builds that understand refresh tokens, so a session
        // signed in before the update gets one without logging in again.
        // Older builds don't send it and get the original response.
        @RequestParam(name = "upgrade", required = false, defaultValue = "false") upgrade: Boolean,
        request: HttpServletRequest
    ): ResponseEntity<Any> {
        val uid = SecurityContextHolder.getContext().authentication?.name
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse(error = "Unauthorized"))

        // Account deleted since the token was issued → no new token.
        if (!userRepo.existsById(uid)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse(error = "Unauthorized"))
        }

        // Suspended since the token was issued → no new token.
        if (userRepo.findSuspendedById(uid) == true) {
            logger.warn("🚫 Refresh refused: user $uid is suspended")
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ErrorResponse(
                error = "Account suspended",
                message = SUSPENDED_MESSAGE
            ))
        }

        if (upgrade) {
            return ResponseEntity.ok(mapOf(
                "accessToken"  to jwt.issueAccess(uid),
                "refreshToken" to issueRefreshSafely(uid, request)
            ))
        }
        return ResponseEntity.ok(mapOf("accessToken" to jwt.issueAccess(uid)))
    }

    /**
     * Long-lived sign-in: swap a refresh token for a new access token AND a new
     * refresh token (the old one is retired). Works even when the access token
     * has long expired — this is what keeps people signed in for weeks instead
     * of minutes. Public (no Bearer needed). See RefreshTokenService.
     *
     * 401 → the refresh token is unknown / expired / revoked / reused, or the
     *       account is gone or suspended. The app should show the login screen.
     */
    @PostMapping("/token")
    fun token(@RequestBody(required = false) r: RefreshReq?): ResponseEntity<Any> {
        val raw = r?.refreshToken?.trim().orEmpty()
        val result = try {
            refreshTokens.rotate(raw)
        } catch (e: Exception) {
            // Database hiccup is NOT "session dead" — 503 so the app retries
            // instead of logging the user out.
            logger.error("❌ Refresh token rotation failed", e)
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ErrorResponse(error = "Try again", message = "Couldn't refresh your session. Please try again."))
        }

        if (result !is RefreshTokenService.RotateResult.Ok) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ErrorResponse(
                error = "Session expired",
                message = "Please sign in again."
            ))
        }

        val uid = result.userId
        if (!userRepo.existsById(uid)) {
            refreshTokens.revokeAllForUser(uid)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse(error = "Unauthorized"))
        }
        if (userRepo.findSuspendedById(uid) == true) {
            logger.warn("🚫 Token refresh refused: user $uid is suspended")
            refreshTokens.revokeAllForUser(uid)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ErrorResponse(
                error = "Account suspended",
                message = SUSPENDED_MESSAGE
            ))
        }

        return ResponseEntity.ok(mapOf(
            "accessToken"  to jwt.issueAccess(uid),
            "refreshToken" to result.refreshToken
        ))
    }

    /**
     * Sign out this device: revokes its refresh token so a copy of it can't be
     * used later. Public and always 204 — the app clears its own storage
     * whether or not this call succeeds.
     */
    @PostMapping("/logout")
    fun logout(@RequestBody(required = false) r: RefreshReq?): ResponseEntity<Void> {
        r?.refreshToken?.trim()?.takeIf { it.isNotEmpty() }?.let {
            runCatching { refreshTokens.revoke(it) }
                .onFailure { e -> logger.warn("Logout revoke failed: ${e.message}") }
        }
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/login")
    fun login(@RequestBody r: LoginReq, request: HttpServletRequest): ResponseEntity<Any> {
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

        // ── Per-account limit (in addition to the per-IP limit in RateLimitFilter)
        val emailKey = r.email.trim().lowercase()
        val acctBucket = rateLimiter.resolveAccountLoginBucket(emailKey)
        if (acctBucket.availableTokens <= 0) {
            val retryAfter = acctBucket.estimateAbilityToConsume(1).nanosToWaitForRefill / 1_000_000_000
            logger.warn("⚠️ Account login limit hit for ${r.email}")
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", retryAfter.toString())
                .body(mapOf(
                    "error" to "Rate limit exceeded",
                    "message" to "Too many failed login attempts. Please wait before trying again.",
                    "retryAfter" to retryAfter,
                    "retryAfterFormatted" to (if (retryAfter < 60) "$retryAfter seconds" else "${retryAfter / 60} minutes"),
                    "type" to "login",
                    "timestamp" to System.currentTimeMillis()
                ))
        }

        val u = userRepo.findByEmail(r.email)

        // Always run one bcrypt check so both failure paths take the same time.
        val passwordOk = pwd.matches(r.password, u?.passwordHash ?: dummyHash)

        if (u == null || !passwordOk) {
            acctBucket.tryConsume(1)
            if (u == null) logger.warn("❌ Login failed: User not found - ${r.email}")
            else logger.warn("❌ Login failed: Invalid password for ${r.email}")
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ErrorResponse(
                error = "Invalid credentials",
                message = "Incorrect email or password. Please try again."
            ))
        }

        rateLimiter.resetAccountLoginBucket(emailKey)

        // Remember this address; alert (or suspend, per /admin) if it's on the ban list.
        fraudGuard.onLogin(u, clientIp.of(request))

        // Checked only AFTER the password is correct, so a wrong guess can't
        // reveal whether an account is suspended.
        if (u.suspended) {
            logger.warn("🚫 Login refused: user ${u.id} is suspended (${u.suspensionReason ?: "no reason"})")
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorResponse(
                error = "Account suspended",
                message = SUSPENDED_MESSAGE
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
                "refreshToken"             to issueRefreshSafely(u.id, request),
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

    /**
     * A refresh-token problem must never fail a login that otherwise worked —
     * older behaviour (short session) is the fallback, signalled by null.
     */
    private fun issueRefreshSafely(userId: UUID, request: HttpServletRequest): String? =
        try {
            refreshTokens.issue(userId, request.getHeader("User-Agent"))
        } catch (e: Exception) {
            logger.error("❌ Could not issue refresh token for $userId", e)
            null
        }

    private fun isValidEmail(email: String): Boolean {
        val emailRegex = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$".toRegex()
        return email.matches(emailRegex)
    }
}