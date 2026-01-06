package com.taptaptips.server.web

import com.taptaptips.server.domain.AppUser
import com.taptaptips.server.repo.AppUserRepository
import com.taptaptips.server.security.JwtService
import com.taptaptips.server.service.StripePaymentService
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/auth")
class AuthController(
    private val userRepo: AppUserRepository,
    private val jwt: JwtService,
    private val stripeService: StripePaymentService  // NEW: Inject Stripe service
) {
    private val pwd: PasswordEncoder = BCryptPasswordEncoder()
    private val logger = LoggerFactory.getLogger(javaClass)

    data class RegisterReq(
        val email: String, 
        val password: String, 
        val displayName: String,
        val wantsToReceiveTips: Boolean = false  // NEW: Add this field
    )
    
    data class LoginReq(val email: String, val password: String)

    @PostMapping("/register")
    fun register(@RequestBody r: RegisterReq): ResponseEntity<Any> {
        logger.info("📝 Registration: email=${r.email}, wantsToReceiveTips=${r.wantsToReceiveTips}")
        
        if (userRepo.existsByEmail(r.email)) {
            return ResponseEntity.status(409).body("email exists")
        }
        
        // Create user
        val u = userRepo.save(AppUser(
            email = r.email, 
            passwordHash = pwd.encode(r.password), 
            displayName = r.displayName,
            wantsToReceiveTips = r.wantsToReceiveTips  // NEW: Save preference
        ))
        
        logger.info("✅ Created user ${u.id}")
        
        // NEW: If user wants to receive tips, create Stripe account
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
            "wantsToReceiveTips" to u.wantsToReceiveTips,  // NEW
            "requiresStripeOnboarding" to r.wantsToReceiveTips,  // NEW
            "stripeOnboardingUrl" to stripeOnboardingUrl,  // NEW
            "isStripeOnboarded" to false  // NEW
        ))
    }

    @PostMapping("/login")
    fun login(@RequestBody r: LoginReq): ResponseEntity<Any> {
        logger.info("🔐 Login attempt: email=${r.email}")
        
        val u = userRepo.findByEmail(r.email) 
            ?: return ResponseEntity.status(401).body("bad credentials")
        
        if (!pwd.matches(r.password, u.passwordHash)) {
            return ResponseEntity.status(401).body("bad credentials")
        }
        
        logger.info("✅ User ${u.id} logged in")
        
        // NEW: Check if user needs to complete Stripe onboarding
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
            "wantsToReceiveTips" to u.wantsToReceiveTips,  // NEW
            "isStripeOnboarded" to u.stripeOnboarded,  // NEW
            "requiresStripeOnboarding" to needsOnboarding,  // NEW
            "stripeOnboardingUrl" to onboardingUrl  // NEW
        ))
    }
}