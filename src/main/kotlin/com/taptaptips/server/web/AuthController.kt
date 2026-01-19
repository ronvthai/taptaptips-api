package com.taptaptips.server.web

import com.taptaptips.server.domain.AppUser
import com.taptaptips.server.repo.AppUserRepository
import com.taptaptips.server.security.JwtService
import com.taptaptips.server.service.StripePaymentService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/auth")
class AuthController(
    private val userRepo: AppUserRepository,
    private val jwt: JwtService,
    private val stripeService: StripePaymentService
) {
    private val pwd: PasswordEncoder = BCryptPasswordEncoder()
    private val logger = LoggerFactory.getLogger(javaClass)

    data class RegisterReq(
        val email: String, 
        val password: String, 
        val displayName: String,
        val wantsToReceiveTips: Boolean = false
    )
    
    data class LoginReq(val email: String, val password: String)
    
    data class ErrorResponse(
        val error: String,
        val message: String? = null,
        val timestamp: Long = System.currentTimeMillis()
    )

    @PostMapping("/register")
    fun register(@RequestBody r: RegisterReq): ResponseEntity<Any> {
        logger.info("📝 Registration: email=${r.email}, wantsToReceiveTips=${r.wantsToReceiveTips}")
        
        // Validate email format
        if (!isValidEmail(r.email)) {
            logger.warn("❌ Registration failed: Invalid email format - ${r.email}")
            return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse(
                    error = "Invalid email format",
                    message = "Please enter a valid email address"
                ))
        }
        
        // Validate password strength
        if (r.password.length < 6) {
            logger.warn("❌ Registration failed: Password too short")
            return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse(
                    error = "Password too short",
                    message = "Password must be at least 6 characters long"
                ))
        }
        
        // Check if email already exists
        if (userRepo.existsByEmail(r.email)) {
            logger.warn("❌ Registration failed: Email already exists - ${r.email}")
            return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ErrorResponse(
                    error = "Email already registered",
                    message = "An account with this email already exists. Please sign in instead."
                ))
        }
        
        try {
            // Create user
            val u = userRepo.save(AppUser(
                email = r.email, 
                passwordHash = pwd.encode(r.password), 
                displayName = r.displayName,
                wantsToReceiveTips = r.wantsToReceiveTips
            ))
            
            logger.info("✅ Created user ${u.id}")
            
            // If user wants to receive tips, create Stripe account
            var stripeOnboardingUrl: String? = null
            if (r.wantsToReceiveTips) {
                try {
                    logger.info("🏦 Creating Stripe account for user ${u.id}...")
                    
                    val stripeAccountId = stripeService.createConnectedAccount(u)
                    stripeOnboardingUrl = stripeService.createAccountLink(stripeAccountId)
                    
                    logger.info("✅ Stripe account created: $stripeAccountId")
                    logger.info("✅ Onboarding URL: $stripeOnboardingUrl")
                    
                } catch (e: Exception) {
                    logger.error("❌ Failed to create Stripe account", e)
                    // Don't fail registration - user can set up later
                }
            }
            
            // Return response with Stripe info
            return ResponseEntity.ok(mapOf(
                "accessToken" to jwt.issueAccess(u.id), 
                "userId" to u.id.toString(),
                "username" to u.displayName,
                "wantsToReceiveTips" to u.wantsToReceiveTips,
                "requiresStripeOnboarding" to r.wantsToReceiveTips,
                "stripeOnboardingUrl" to stripeOnboardingUrl,
                "isStripeOnboarded" to false
            ))
        } catch (e: Exception) {
            logger.error("❌ Registration failed with exception", e)
            return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse(
                    error = "Registration failed",
                    message = "An unexpected error occurred. Please try again."
                ))
        }
    }

    @PostMapping("/login")
    fun login(@RequestBody r: LoginReq): ResponseEntity<Any> {
        logger.info("🔐 Login attempt: email=${r.email}")
        
        // Validate email format
        if (!isValidEmail(r.email)) {
            logger.warn("❌ Login failed: Invalid email format")
            return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse(
                    error = "Invalid email",
                    message = "Please enter a valid email address"
                ))
        }
        
        // Find user
        val u = userRepo.findByEmail(r.email)
        
        if (u == null) {
            logger.warn("❌ Login failed: User not found - ${r.email}")
            return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse(
                    error = "Invalid credentials",
                    message = "Incorrect email or password. Please try again."
                ))
        }
        
        // Check password
        if (!pwd.matches(r.password, u.passwordHash)) {
            logger.warn("❌ Login failed: Invalid password for ${r.email}")
            return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse(
                    error = "Invalid credentials",
                    message = "Incorrect email or password. Please try again."
                ))
        }
        
        logger.info("✅ User ${u.id} logged in successfully")
        
        try {
            // Check if user needs to complete Stripe onboarding
            var needsOnboarding = false
            var onboardingUrl: String? = null
            
            if (u.wantsToReceiveTips && !u.stripeOnboarded) {
                needsOnboarding = true
                
                // Generate new onboarding link if needed
                if (u.stripeAccountId != null) {
                    try {
                        onboardingUrl = stripeService.createAccountLink(u.stripeAccountId!!)
                        logger.info("✅ Generated onboarding URL for user ${u.id}")
                    } catch (e: Exception) {
                        logger.error("❌ Failed to create onboarding link", e)
                    }
                }
            }
            
            return ResponseEntity.ok(mapOf(
                "accessToken" to jwt.issueAccess(u.id), 
                "userId" to u.id.toString(),
                "username" to u.displayName,
                "wantsToReceiveTips" to u.wantsToReceiveTips,
                "isStripeOnboarded" to u.stripeOnboarded,
                "requiresStripeOnboarding" to needsOnboarding,
                "stripeOnboardingUrl" to onboardingUrl
            ))
        } catch (e: Exception) {
            logger.error("❌ Login successful but post-login operations failed", e)
            return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse(
                    error = "Login error",
                    message = "An unexpected error occurred. Please try again."
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