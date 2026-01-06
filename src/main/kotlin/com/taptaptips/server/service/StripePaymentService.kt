package com.taptaptips.server.service

import com.stripe.exception.StripeException
import com.stripe.model.*
import com.stripe.param.*
import com.taptaptips.server.domain.AppUser
import com.taptaptips.server.repo.AppUserRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.util.*

@Service
class StripePaymentService(
    private val userRepository: AppUserRepository,
    @Value("\${app.base.url:http://192.168.1.153}") 
    private val baseUrl: String,
    @Value("\${stripe.platform.fee.percent:0}") 
    private val platformFeePercent: Int
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    
    // ==================== RECEIVER METHODS (EXISTING) ====================
    
    /**
     * Create a Stripe Connected Account for receiving tips
     */
    @Transactional
    fun createConnectedAccount(user: AppUser): String {
        // Don't create if already exists
        if (user.stripeAccountId != null) {
            logger.info("User ${user.id} already has Stripe account: ${user.stripeAccountId}")
            return user.stripeAccountId!!
        }
        
        try {
            // Parse display name into first/last name (best effort)
            val nameParts = user.displayName.trim().split(" ", limit = 2)
            val firstName = nameParts.getOrNull(0) ?: user.displayName
            val lastName = nameParts.getOrNull(1) ?: ""
            
            val accountParams = AccountCreateParams.builder()
                .setType(AccountCreateParams.Type.EXPRESS)
                .setEmail(user.email)
                .setBusinessType(AccountCreateParams.BusinessType.INDIVIDUAL)
                // Pre-fill individual info
                .setIndividual(
                    AccountCreateParams.Individual.builder()
                        .setEmail(user.email)
                        .setFirstName(firstName)
                        .setLastName(lastName)
                        .build()
                )
                .setCapabilities(
                    AccountCreateParams.Capabilities.builder()
                        .setCardPayments(
                            AccountCreateParams.Capabilities.CardPayments.builder()
                                .setRequested(true)
                                .build()
                        )
                        .setTransfers(
                            AccountCreateParams.Capabilities.Transfers.builder()
                                .setRequested(true)
                                .build()
                        )
                        .build()
                )
                // Pre-fill business profile to avoid manual entry during onboarding
                .setBusinessProfile(
                    AccountCreateParams.BusinessProfile.builder()
                        .setMcc("7399")  // Personal services MCC code
                        .setProductDescription("Personal tipping and service payments")  // Pre-fill description
                        .build()
                )
                .setMetadata(
                    mapOf(
                        "user_id" to user.id.toString(),
                        "email" to user.email,
                        "display_name" to user.displayName
                    )
                )
                .build()
            
            val account = Account.create(accountParams)
            
            // Save to database
            user.stripeAccountId = account.id
            user.stripeOnboarded = false
            user.stripeLastChecked = Instant.now()
            user.updatedAt = Instant.now()
            userRepository.save(user)
            
            logger.info("✅ Created Stripe INDIVIDUAL account ${account.id} for user ${user.id}")
            
            return account.id
            
        } catch (e: StripeException) {
            logger.error("❌ Failed to create Stripe account for user ${user.id}", e)
            throw RuntimeException("Failed to create payment account: ${e.message}", e)
        }
    }
    
    /**
     * Create an onboarding link for user to complete Stripe setup
     * Links expire after 5 minutes!
     */
    fun createAccountLink(stripeAccountId: String): String {
        try {
            val returnUrl = "$baseUrl/stripe/onboarding/complete"
            val refreshUrl = "$baseUrl/stripe/onboarding/refresh"
            
            val linkParams = AccountLinkCreateParams.builder()
                .setAccount(stripeAccountId)
                .setRefreshUrl(refreshUrl)
                .setReturnUrl(returnUrl)
                .setType(AccountLinkCreateParams.Type.ACCOUNT_ONBOARDING)
                .setCollect(AccountLinkCreateParams.Collect.CURRENTLY_DUE)  // Most aggressive - only what's needed now
                .build()
            
            val accountLink = AccountLink.create(linkParams)
            
            logger.info("✅ Created onboarding link for account $stripeAccountId")
            logger.info("   URL: ${accountLink.url}")
            
            return accountLink.url
            
        } catch (e: StripeException) {
            logger.error("❌ Failed to create account link for $stripeAccountId", e)
            throw RuntimeException("Failed to create onboarding link: ${e.message}", e)
        }
    }
    
    @Transactional
    fun checkAndUpdateOnboardingStatus(user: AppUser): Boolean {
        if (user.stripeAccountId == null) {
            logger.warn("⚠️ User ${user.id} has no Stripe account ID")
            return false
        }
        
        try {
            logger.info("🔍 Retrieving Stripe account: ${user.stripeAccountId}")
            val account = Account.retrieve(user.stripeAccountId)
            
            // Log detailed account status
            logger.info("📊 Stripe Account Status:")
            logger.info("   - chargesEnabled: ${account.chargesEnabled}")
            logger.info("   - payoutsEnabled: ${account.payoutsEnabled}")
            logger.info("   - detailsSubmitted: ${account.detailsSubmitted}")
            logger.info("   - requirements: ${account.requirements}")
            
            if (account.requirements != null) {
                logger.info("   - currently_due: ${account.requirements.currentlyDue}")
                logger.info("   - eventually_due: ${account.requirements.eventuallyDue}")
                logger.info("   - past_due: ${account.requirements.pastDue}")
            }
            
            // Check if onboarded (both charges AND payouts must be enabled)
            val isOnboarded = account.chargesEnabled && account.payoutsEnabled
            
            logger.info("✅ Onboarding status: $isOnboarded")
            
            // Update if status changed
            if (user.stripeOnboarded != isOnboarded) {
                user.stripeOnboarded = isOnboarded
                user.stripeLastChecked = Instant.now()
                user.updatedAt = Instant.now()
                
                // Save bank info if available
                if (isOnboarded && account.externalAccounts != null) {
                    logger.info("💳 Checking for bank account info...")
                    val bankAccount = account.externalAccounts.data
                        .filterIsInstance<BankAccount>()
                        .firstOrNull()
                    
                    if (bankAccount != null) {
                        user.bankLast4 = bankAccount.last4
                        user.bankName = bankAccount.bankName
                        logger.info("   - Bank: ${bankAccount.bankName} ****${bankAccount.last4}")
                    } else {
                        logger.warn("   - No bank account found in external accounts")
                    }
                }
                
                userRepository.save(user)
                
                logger.info("💾 User ${user.id} onboarding status updated: $isOnboarded")
            }
            
            return isOnboarded
            
        } catch (e: StripeException) {
            logger.error("❌ Failed to check onboarding status for ${user.stripeAccountId}", e)
            return false
        }
    }
    
    // ==================== SENDER METHODS (NEW) ====================
    
    /**
     * Get or create a Stripe Customer for a user (for sending tips)
     * Customers can save payment methods for reuse
     */
    @Transactional
    fun getOrCreateCustomer(user: AppUser): String {
        // Return existing customer if already created
        if (user.stripeCustomerId != null) {
            logger.info("User ${user.id} already has Stripe customer: ${user.stripeCustomerId}")
            return user.stripeCustomerId!!
        }
        
        try {
            val params = CustomerCreateParams.builder()
                .setEmail(user.email)
                .setName(user.displayName)
                .putMetadata("user_id", user.id.toString())
                .build()
            
            val customer = Customer.create(params)
            
            // Save customer ID to user
            user.stripeCustomerId = customer.id
            user.updatedAt = Instant.now()
            userRepository.save(user)
            
            logger.info("✅ Created Stripe customer ${customer.id} for user ${user.id}")
            
            return customer.id
            
        } catch (e: StripeException) {
            logger.error("❌ Failed to create Stripe customer for user ${user.id}", e)
            throw RuntimeException("Failed to create customer: ${e.message}", e)
        }
    }
    
    /**
     * Create a SetupIntent for saving a payment method
     * This is used when user first adds their card
     */
    fun createSetupIntent(customerId: String): SetupIntent {
        try {
            val params = SetupIntentCreateParams.builder()
                .setCustomer(customerId)
                .setAutomaticPaymentMethods(
                    SetupIntentCreateParams.AutomaticPaymentMethods.builder()
                        .setEnabled(true)
                        .build()
                )
                .build()
            
            val setupIntent = SetupIntent.create(params)
            
            logger.info("✅ Created setup intent ${setupIntent.id} for customer $customerId")
            
            return setupIntent
            
        } catch (e: StripeException) {
            logger.error("❌ Failed to create setup intent for customer $customerId", e)
            throw RuntimeException("Failed to create setup intent: ${e.message}", e)
        }
    }
    
    /**
     * List saved payment methods for a customer
     */
    fun listPaymentMethods(customerId: String): List<PaymentMethod> {
        try {
            val params = PaymentMethodListParams.builder()
                .setCustomer(customerId)
                .setType(PaymentMethodListParams.Type.CARD)
                .build()
            
            val methods = PaymentMethod.list(params).data
            
            logger.info("📋 Listed ${methods.size} payment methods for customer $customerId")
            
            return methods
            
        } catch (e: StripeException) {
            logger.error("❌ Failed to list payment methods for customer $customerId", e)
            throw RuntimeException("Failed to list payment methods: ${e.message}", e)
        }
    }
    
    /**
     * Attach a payment method to a customer and set as default
     */
    @Transactional
    fun attachPaymentMethod(paymentMethodId: String, customerId: String, user: AppUser): PaymentMethod {
        try {
            logger.info("📎 Attaching payment method $paymentMethodId to customer $customerId")
            
            // Attach to customer
            val paymentMethod = PaymentMethod.retrieve(paymentMethodId)
            val attachParams = PaymentMethodAttachParams.builder()
                .setCustomer(customerId)
                .build()
            paymentMethod.attach(attachParams)
            
            logger.info("✅ Payment method attached")
            
            // Set as default payment method on customer
            val updateParams = CustomerUpdateParams.builder()
                .setInvoiceSettings(
                    CustomerUpdateParams.InvoiceSettings.builder()
                        .setDefaultPaymentMethod(paymentMethodId)
                        .build()
                )
                .build()
            Customer.retrieve(customerId).update(updateParams)
            
            logger.info("✅ Set as default payment method on customer")
            
            // Save to user record
            user.defaultPaymentMethodId = paymentMethodId
            user.updatedAt = Instant.now()
            userRepository.save(user)
            
            logger.info("✅ Saved default payment method to user ${user.id}")
            
            return paymentMethod
            
        } catch (e: StripeException) {
            logger.error("❌ Failed to attach payment method $paymentMethodId", e)
            throw RuntimeException("Failed to attach payment method: ${e.message}", e)
        }
    }
    
    /**
     * Detach (remove) a payment method from customer
     */
    fun detachPaymentMethod(paymentMethodId: String) {
        try {
            logger.info("🗑️ Detaching payment method $paymentMethodId")
            
            val paymentMethod = PaymentMethod.retrieve(paymentMethodId)
            paymentMethod.detach()
            
            logger.info("✅ Payment method detached")
            
        } catch (e: StripeException) {
            logger.error("❌ Failed to detach payment method $paymentMethodId", e)
            throw RuntimeException("Failed to detach payment method: ${e.message}", e)
        }
    }
    
    /**
     * Create PaymentIntent using saved payment method
     * This is the fast path for tips with saved cards - auto-confirms!
     * 
     * FIXED: Added customer parameter (required when using saved payment method)
     */
    fun createPaymentIntentWithSavedMethod(
        amount: BigDecimal,
        receiverStripeAccountId: String,
        senderId: UUID,
        receiverId: UUID,
        tipNonce: String,
        paymentMethodId: String,
        senderCustomerId: String  // ADDED: Customer ID is required
    ): PaymentIntent {
        try {
            logger.info("💳 Creating payment intent with saved method")
            logger.info("   - Amount: $$amount")
            logger.info("   - Sender: $senderId")
            logger.info("   - Receiver: $receiverId")
            logger.info("   - Payment Method: $paymentMethodId")
            logger.info("   - Customer: $senderCustomerId")
            
            val amountInCents = (amount.multiply(BigDecimal(100))).toLong()
            val applicationFee = (amountInCents * platformFeePercent) / 100
            
            logger.info("   - Amount in cents: $amountInCents")
            logger.info("   - Platform fee: $applicationFee cents ($platformFeePercent%)")
            
            val params = PaymentIntentCreateParams.builder()
                .setAmount(amountInCents)
                .setCurrency("usd")
                .setCustomer(senderCustomerId)  // FIXED: Must include customer when using saved payment method
                .setApplicationFeeAmount(applicationFee)
                .setPaymentMethod(paymentMethodId)  // Use saved card
                .setConfirm(true)  // Auto-confirm (no extra step needed!)
                .setOffSession(true)  // Allow charging when user is offline
                .setTransferData(
                    PaymentIntentCreateParams.TransferData.builder()
                        .setDestination(receiverStripeAccountId)
                        .build()
                )
                .putMetadata("sender_id", senderId.toString())
                .putMetadata("receiver_id", receiverId.toString())
                .putMetadata("tip_nonce", tipNonce)
                .build()
            
            val paymentIntent = PaymentIntent.create(params)
            
            logger.info("✅ Payment intent created: ${paymentIntent.id}")
            logger.info("   - Status: ${paymentIntent.status}")
            logger.info("   - Amount charged: ${paymentIntent.amount} cents")
            
            return paymentIntent
            
        } catch (e: StripeException) {
            logger.error("❌ Failed to create payment intent", e)
            logger.error("   - Error type: ${e.javaClass.simpleName}")
            logger.error("   - Error message: ${e.message}")
            throw RuntimeException("Failed to create payment intent: ${e.message}", e)
        }
    }
}