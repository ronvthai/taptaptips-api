package com.taptaptips.server.web

import com.taptaptips.server.repo.AppUserRepository
import com.taptaptips.server.service.StripePaymentService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.util.*

@RestController
@RequestMapping("/stripe")
class StripeController(
    private val stripeService: StripePaymentService,
    private val userRepository: AppUserRepository
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    
    private fun authUserId() = UUID.fromString(
        SecurityContextHolder.getContext().authentication.name
    )
    
    // ==================== RECEIVER ENDPOINTS (EXISTING) ====================
    
    /**
     * Check if user has completed Stripe onboarding
     */
    @GetMapping("/receiver/status/{userId}")
    fun getOnboardingStatus(@PathVariable userId: String): OnboardingStatusResponse {
        logger.info("📊 Checking onboarding status for user $userId")

        // IDOR fix: caller must be the same user they are querying
        val callerId = authUserId()
        if (callerId != UUID.fromString(userId)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "You may only check your own onboarding status")
        }

        val user = userRepository.findById(UUID.fromString(userId)).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")
        }
        
        // Check current status with Stripe
        val isOnboarded = stripeService.checkAndUpdateOnboardingStatus(user)
        
        logger.info("✅ User $userId onboarding status: $isOnboarded")
        
        return OnboardingStatusResponse(
            isOnboarded = isOnboarded,
            accountId = user.stripeAccountId,
            bankLast4 = user.bankLast4,
            bankName = user.bankName
        )
    }
    
    /**
     * Get new onboarding link (for completing or redoing onboarding)
     */
    @GetMapping("/receiver/onboarding-link/{userId}")
    fun getOnboardingLink(@PathVariable userId: String): OnboardingLinkResponse {
        logger.info("🔗 Generating onboarding link for user $userId")

        // IDOR fix: caller must be the same user they are requesting a link for
        val callerId = authUserId()
        if (callerId != UUID.fromString(userId)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "You may only request your own onboarding link")
        }

        val user = userRepository.findById(UUID.fromString(userId)).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")
        }
        
        // Create Stripe account if doesn't exist
        if (user.stripeAccountId == null) {
            logger.info("📝 Creating Stripe account for user $userId...")
            val accountId = stripeService.createConnectedAccount(user)
            logger.info("✅ Created Stripe account: $accountId")
        }
        
        // Generate onboarding link
        val url = stripeService.createAccountLink(user.stripeAccountId!!)
        
        logger.info("✅ Generated onboarding URL: $url")
        
        return OnboardingLinkResponse(url = url)
    }
    
    // // ==================== ADMIN/TEST ENDPOINTS (TEMPORARY - REMOVE BEFORE PRODUCTION!) ====================
    
    // /**
    //  * Delete a specific Stripe test account
    //  * TEMPORARY - Remove before production!
    //  */
    // @GetMapping("/admin/delete-account/{accountId}")
    // fun deleteStripeAccount(@PathVariable accountId: String): Map<String, String> {
    //     return try {
    //         val account = com.stripe.model.Account.retrieve(accountId)
    //         account.delete()
    //         logger.info("✅ Deleted Stripe account: $accountId")
    //         mapOf("status" to "success", "message" to "Deleted account: $accountId")
    //     } catch (e: com.stripe.exception.StripeException) {
    //         logger.error("❌ Failed to delete account $accountId", e)
    //         mapOf("status" to "error", "message" to "Failed: ${e.message}")
    //     }
    // }
    
    // /**
    //  * Delete ALL test Stripe accounts
    //  * TEMPORARY - Remove before production!
    //  */
    // @GetMapping("/admin/delete-all-accounts")
    // fun deleteAllTestAccounts(): Map<String, Any> {
    //     val results = mutableListOf<String>()
    //     var deleted = 0
    //     var failed = 0
        
    //     try {
    //         logger.info("🧹 Starting cleanup of all test Stripe accounts...")
    //         val accounts = com.stripe.model.Account.list(
    //             com.stripe.param.AccountListParams.builder().setLimit(100).build()
    //         )
            
    //         logger.info("   Found ${accounts.data.size} accounts to delete")
            
    //         accounts.data.forEach { account ->
    //             try {
    //                 account.delete()
    //                 results.add("✅ Deleted: ${account.id}")
    //                 deleted++
    //                 logger.info("   Deleted: ${account.id}")
    //             } catch (e: Exception) {
    //                 results.add("❌ Failed: ${account.id} - ${e.message}")
    //                 failed++
    //                 logger.error("   Failed: ${account.id}", e)
    //             }
    //         }
            
    //         logger.info("✅ Cleanup complete: $deleted deleted, $failed failed")
            
    //         return mapOf(
    //             "status" to "success",
    //             "deleted" to deleted,
    //             "failed" to failed,
    //             "details" to results
    //         )
    //     } catch (e: Exception) {
    //         logger.error("❌ Cleanup failed", e)
    //         return mapOf(
    //             "status" to "error",
    //             "error" to (e.message ?: "Unknown error")
    //         )
    //     }
    // }
    
    // /**
    //  * Clean database Stripe references for a specific user
    //  * TEMPORARY - Remove before production!
    //  */
    // @GetMapping("/admin/clean-user-stripe/{userId}")
    // fun cleanUserStripeData(@PathVariable userId: String): Map<String, String> {
    //     return try {
    //         val user = userRepository.findById(UUID.fromString(userId)).orElseThrow {
    //             ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")
    //         }
            
    //         logger.info("🧹 Cleaning Stripe data for user $userId")
    //         logger.info("   Old account ID: ${user.stripeAccountId}")
    //         logger.info("   Old customer ID: ${user.stripeCustomerId}")
            
    //         user.stripeAccountId = null
    //         user.stripeOnboarded = false
    //         user.stripeCustomerId = null
    //         user.defaultPaymentMethodId = null
    //         user.bankLast4 = null
    //         user.bankName = null
    //         user.stripeLastChecked = null
    //         user.updatedAt = java.time.Instant.now()
            
    //         userRepository.save(user)
            
    //         logger.info("✅ Cleaned Stripe data for user $userId")
            
    //         mapOf("status" to "success", "message" to "Cleaned user $userId")
    //     } catch (e: Exception) {
    //         logger.error("❌ Failed to clean user data", e)
    //         mapOf("status" to "error", "message" to "Failed: ${e.message}")
    //     }
    // }
    
    // // ==================== END ADMIN/TEST ENDPOINTS ====================
    
    /**
     * Callback when user completes onboarding
     * (Called by Stripe after user finishes)
     */
    @GetMapping("/onboarding/complete")
    fun onboardingComplete(): String {
        logger.info("✅ Onboarding complete callback received")
        return """
            <html>
            <head><title>Setup Complete</title></head>
            <body style="font-family: Arial; text-align: center; padding: 50px;">
                <h1>✅ Setup Complete!</h1>
                <p>You can now close this window and return to the app.</p>
                <script>
                    setTimeout(function() {
                        window.close();
                    }, 2000);
                </script>
            </body>
            </html>
        """.trimIndent()
    }
    
    /**
     * Callback when onboarding link expires
     * (Called by Stripe if link expired)
     */
    @GetMapping("/onboarding/refresh")
    fun onboardingRefresh(@RequestParam(required = false) account: String?): String {
        logger.info("🔄 Onboarding refresh callback received for account: $account")
        return """
            <html>
            <head><title>Link Expired</title></head>
            <body style="font-family: Arial; text-align: center; padding: 50px;">
                <h1>⚠️ Link Expired</h1>
                <p>Please return to the app to get a new setup link.</p>
            </body>
            </html>
        """.trimIndent()
    }
    
    // ==================== SENDER ENDPOINTS (NEW) ====================
    
    /**
     * Create setup intent for adding a new payment method
     */
    @PostMapping("/payment-methods/setup")
    fun createSetupIntent(): SetupIntentResponse {
        val userId = authUserId()
        val user = userRepository.findById(userId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")
        }
        
        logger.info("💳 Creating setup intent for user $userId")
        
        // Get or create Stripe customer
        val customerId = stripeService.getOrCreateCustomer(user)
        
        // Create setup intent
        val setupIntent = stripeService.createSetupIntent(customerId)
        
        logger.info("✅ Setup intent created: ${setupIntent.id}")
        
        return SetupIntentResponse(
            clientSecret = setupIntent.clientSecret!!,
            customerId = customerId
        )
    }
    
    /**
     * Confirm payment method was saved and set as default
     */
    @PostMapping("/payment-methods/confirm")
    fun confirmPaymentMethod(
        @RequestBody request: ConfirmPaymentMethodRequest
    ): PaymentMethodResponse {
        val userId = authUserId()
        val user = userRepository.findById(userId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")
        }
        
        if (user.stripeCustomerId == null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "No customer ID")
        }
        
        logger.info("✅ Confirming payment method ${request.paymentMethodId} for user $userId")
        
        // Attach payment method to customer and set as default
        val paymentMethod = stripeService.attachPaymentMethod(
            request.paymentMethodId,
            user.stripeCustomerId!!,
            user
        )
        
        logger.info("✅ Payment method confirmed and saved")
        
        return PaymentMethodResponse(
            paymentMethodId = paymentMethod.id,
            brand = paymentMethod.card?.brand ?: "unknown",
            last4 = paymentMethod.card?.last4 ?: "****"
        )
    }
    
    /**
     * List user's saved payment methods
     */
    @GetMapping("/payment-methods")
    fun listPaymentMethods(): PaymentMethodsListResponse {
        val userId = authUserId()
        val user = userRepository.findById(userId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")
        }
        
        logger.info("📋 Listing payment methods for user $userId")
        
        if (user.stripeCustomerId == null) {
            logger.info("   User has no Stripe customer ID")
            return PaymentMethodsListResponse(emptyList(), null)
        }
        
        val methods = stripeService.listPaymentMethods(user.stripeCustomerId!!)
        
        logger.info("   Found ${methods.size} payment methods")
        
        return PaymentMethodsListResponse(
            paymentMethods = methods.map {
                SavedPaymentMethod(
                    id = it.id,
                    brand = it.card?.brand ?: "unknown",
                    last4 = it.card?.last4 ?: "****",
                    expMonth = it.card?.expMonth?.toInt() ?: 0,
                    expYear = it.card?.expYear?.toInt() ?: 0
                )
            },
            defaultPaymentMethodId = user.defaultPaymentMethodId
        )
    }
    
    /**
     * Delete a saved payment method
     */
    @DeleteMapping("/payment-methods/{paymentMethodId}")
    fun deletePaymentMethod(@PathVariable paymentMethodId: String) {
        val userId = authUserId()
        val user = userRepository.findById(userId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")
        }
        
        logger.info("🗑️ Deleting payment method $paymentMethodId for user $userId")
        
        // Detach from Stripe
        stripeService.detachPaymentMethod(paymentMethodId)
        
        // If this was the default, clear it
        if (user.defaultPaymentMethodId == paymentMethodId) {
            user.defaultPaymentMethodId = null
            user.updatedAt = java.time.Instant.now()
            userRepository.save(user)
            logger.info("   Cleared default payment method")
        }
        
        logger.info("✅ Payment method deleted")
    }
    
    /**
     * Calculate fee breakdown for a given amount
     * Shows sender what receiver will actually receive after fees
     */
    @GetMapping("/calculate-fees")
    fun calculateFees(@RequestParam amount: BigDecimal): FeeBreakdownResponse {
        logger.info("🧮 Calculating fees for amount: $$amount")
        
        val breakdown = stripeService.calculateFeeBreakdown(amount)
        
        logger.info("   Sender pays: $${breakdown.senderPays}")
        logger.info("   Stripe fee: $${breakdown.stripeFee}")
        logger.info("   Platform fee: $${breakdown.platformFee}")
        logger.info("   Total fee: $${breakdown.totalFee}")
        logger.info("   Receiver gets: $${breakdown.receiverGets}")
        
        return FeeBreakdownResponse(
            senderPays = breakdown.senderPays,
            stripeFee = breakdown.stripeFee,
            platformFee = breakdown.platformFee,
            totalFee = breakdown.totalFee,
            receiverGets = breakdown.receiverGets,
            stripePercent = "2.9",
            stripeCents = "30",
            platformPercent = "3.0"
        )
    }
    
    /**
     * Create payment intent for tip
     * If paymentMethodId is provided, use that specific card
     * Otherwise, use user's default payment method
     */
     @PostMapping("/payment-intent")
    fun createPaymentIntent(
        @RequestBody request: CreatePaymentIntentRequest
    ): PaymentIntentResponse {
        val senderId = authUserId()
        val sender = userRepository.findById(senderId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Sender not found")
        }
 
        logger.info("💰 Creating payment intent (unconfirmed — client will confirm)")
        logger.info("   Sender: $senderId")
        logger.info("   Receiver: ${request.receiverId}")
        logger.info("   Amount: $${request.amount}")
 
        val receiver = userRepository.findById(request.receiverId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Receiver not found")
        }
 
        if (receiver.stripeAccountId == null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Receiver has not set up payment receiving")
        }
        if (!receiver.stripeOnboarded) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Receiver has not completed Stripe account setup")
        }
 
        val paymentMethodId = request.paymentMethodId ?: sender.defaultPaymentMethodId
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "No payment method available. Please add a card first.")
 
        if (sender.stripeCustomerId == null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Sender customer not found. Please add a payment method first.")
        }
 
        logger.info("   Using payment method: $paymentMethodId")
 
        // Unconfirmed — Stripe iOS SDK confirms using the returned clientSecret
        val paymentIntent = stripeService.createPaymentIntentUnconfirmed(
            amount                  = request.amount,
            receiverStripeAccountId = receiver.stripeAccountId!!,
            senderId                = senderId,
            receiverId              = request.receiverId,
            tipNonce                = request.nonce,
            paymentMethodId         = paymentMethodId,
            senderCustomerId        = sender.stripeCustomerId!!
        )
 
        logger.info("✅ Unconfirmed payment intent created: ${paymentIntent.id}")
 
        return PaymentIntentResponse(
            clientSecret      = paymentIntent.clientSecret,
            paymentIntentId   = paymentIntent.id,
            receiverName      = receiver.displayName,
            receiverBankLast4 = receiver.bankLast4,
            status            = paymentIntent.status   // "requires_confirmation"
        )
    }
}

// ==================== RESPONSE DTOs ====================

// Receiver DTOs (existing)
data class OnboardingStatusResponse(
    val isOnboarded: Boolean,
    val accountId: String?,
    val bankLast4: String?,
    val bankName: String?
)

data class OnboardingLinkResponse(
    val url: String
)

// Sender DTOs (new)
data class SetupIntentResponse(
    val clientSecret: String,
    val customerId: String
)

data class ConfirmPaymentMethodRequest(
    val paymentMethodId: String
)

data class PaymentMethodResponse(
    val paymentMethodId: String,
    val brand: String,
    val last4: String
)

data class SavedPaymentMethod(
    val id: String,
    val brand: String,
    val last4: String,
    val expMonth: Int,
    val expYear: Int
)

data class PaymentMethodsListResponse(
    val paymentMethods: List<SavedPaymentMethod>,
    val defaultPaymentMethodId: String?
)

data class CreatePaymentIntentRequest(
    val receiverId: UUID,
    val amount: BigDecimal,
    val nonce: String,
    val paymentMethodId: String? = null  // Optional - use specific card or default
)

data class PaymentIntentResponse(
    val clientSecret: String?,
    val paymentIntentId: String,
    val receiverName: String,
    val receiverBankLast4: String?,
    val status: String
)

data class FeeBreakdownResponse(
    val senderPays: BigDecimal,        // What sender pays (original amount)
    val stripeFee: BigDecimal,         // Stripe processing fee
    val platformFee: BigDecimal,       // TapTapTips platform fee (3%)
    val totalFee: BigDecimal,          // Total fees deducted
    val receiverGets: BigDecimal,      // What receiver actually receives
    val stripePercent: String,         // "2.9" for display
    val stripeCents: String,           // "30" for display  
    val platformPercent: String        // "3.0" for display
)