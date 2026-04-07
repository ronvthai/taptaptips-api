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
import java.math.RoundingMode
import java.time.Instant
import java.util.*

@Service
class StripePaymentService(
    private val userRepository: AppUserRepository,

    @Value("\${app.base.url}")
    private val baseUrl: String,

    @Value("\${stripe.processing.fee.percent:2.9}")
    private val processingFeePercent: BigDecimal,

    @Value("\${stripe.processing.fee.fixed.cents:30}")
    private val processingFeeFixedCents: Long,

    @Value("\${stripe.platform.fee.fixed.cents:0}")
    private val platformFeeFixedCents: Long,

    @Value("\${stripe.platform.fee.percent:3.0}")
    private val platformFeePercent: BigDecimal,

    @Value("\${stripe.minimum.charge.cents:50}")
    private val minimumChargeCents: Long
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    // ── Receiver onboarding ──────────────────────────────────────────────────

    @Transactional
    fun createConnectedAccount(user: AppUser): String {
        if (user.stripeAccountId != null) {
            logger.info("User ${user.id} already has Stripe account: ${user.stripeAccountId}")
            return user.stripeAccountId!!
        }
        try {
            val nameParts = user.displayName.trim().split(" ", limit = 2)
            val account = Account.create(
                AccountCreateParams.builder()
                    .setType(AccountCreateParams.Type.EXPRESS)
                    .setEmail(user.email)
                    .setBusinessType(AccountCreateParams.BusinessType.INDIVIDUAL)
                    .setIndividual(
                        AccountCreateParams.Individual.builder()
                            .setEmail(user.email)
                            .setFirstName(nameParts.getOrNull(0) ?: user.displayName)
                            .setLastName(nameParts.getOrNull(1) ?: "")
                            .build()
                    )
                    .setCapabilities(
                        AccountCreateParams.Capabilities.builder()
                            .setCardPayments(
                                AccountCreateParams.Capabilities.CardPayments.builder().setRequested(true).build()
                            )
                            .setTransfers(
                                AccountCreateParams.Capabilities.Transfers.builder().setRequested(true).build()
                            )
                            .build()
                    )
                    .setBusinessProfile(
                        AccountCreateParams.BusinessProfile.builder()
                            .setMcc("7399")
                            .setProductDescription("Personal tipping and service payments")
                            .build()
                    )
                    .setMetadata(mapOf(
                        "user_id"      to user.id.toString(),
                        "email"        to user.email,
                        "display_name" to user.displayName
                    ))
                    .build()
            )
            user.stripeAccountId  = account.id
            user.stripeOnboarded  = false
            user.stripeLastChecked = Instant.now()
            user.updatedAt        = Instant.now()
            userRepository.save(user)
            logger.info("✅ Created Stripe account ${account.id} for user ${user.id}")
            return account.id
        } catch (e: StripeException) {
            logger.error("❌ Failed to create Stripe account for user ${user.id}", e)
            throw RuntimeException("Failed to create payment account: ${e.message}", e)
        }
    }

    fun createAccountLink(stripeAccountId: String): String {
        try {
            val link = AccountLink.create(
                AccountLinkCreateParams.builder()
                    .setAccount(stripeAccountId)
                    .setRefreshUrl("$baseUrl/stripe/onboarding/refresh")
                    .setReturnUrl("$baseUrl/stripe/onboarding/complete")
                    .setType(AccountLinkCreateParams.Type.ACCOUNT_ONBOARDING)
                    .setCollect(AccountLinkCreateParams.Collect.CURRENTLY_DUE)
                    .build()
            )
            logger.info("✅ Created onboarding link for account $stripeAccountId")
            return link.url
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
        return try {
            val account     = Account.retrieve(user.stripeAccountId)
            val isOnboarded = account.chargesEnabled && account.payoutsEnabled

            user.stripeOnboarded  = isOnboarded
            user.stripeLastChecked = Instant.now()
            user.updatedAt        = Instant.now()

            if (isOnboarded && account.externalAccounts != null) {
                account.externalAccounts.data
                    .filterIsInstance<BankAccount>()
                    .firstOrNull()
                    ?.let { bank ->
                        user.bankLast4 = bank.last4
                        user.bankName  = bank.bankName
                    }
            }
            userRepository.save(user)
            logger.info("✅ Onboarding status for ${user.id}: $isOnboarded")
            isOnboarded
        } catch (e: StripeException) {
            logger.error("❌ Failed to check onboarding status for ${user.stripeAccountId}", e)
            false
        }
    }

    // ── Sender / customer ────────────────────────────────────────────────────

    @Transactional
    fun getOrCreateCustomer(user: AppUser): String {
        if (user.stripeCustomerId != null) return user.stripeCustomerId!!
        try {
            val customer = Customer.create(
                CustomerCreateParams.builder()
                    .setEmail(user.email)
                    .setName(user.displayName)
                    .putMetadata("user_id", user.id.toString())
                    .build()
            )
            user.stripeCustomerId = customer.id
            user.updatedAt        = Instant.now()
            userRepository.save(user)
            logger.info("✅ Created Stripe customer ${customer.id} for user ${user.id}")
            return customer.id
        } catch (e: StripeException) {
            logger.error("❌ Failed to create Stripe customer for user ${user.id}", e)
            throw RuntimeException("Failed to create customer: ${e.message}", e)
        }
    }

    fun createSetupIntent(customerId: String): SetupIntent {
        try {
            return SetupIntent.create(
                SetupIntentCreateParams.builder()
                    .setCustomer(customerId)
                    .setAutomaticPaymentMethods(
                        SetupIntentCreateParams.AutomaticPaymentMethods.builder().setEnabled(true).build()
                    )
                    .build()
            )
        } catch (e: StripeException) {
            logger.error("❌ Failed to create setup intent for customer $customerId", e)
            throw RuntimeException("Failed to create setup intent: ${e.message}", e)
        }
    }

    fun listPaymentMethods(customerId: String): List<PaymentMethod> {
        try {
            return PaymentMethod.list(
                PaymentMethodListParams.builder()
                    .setCustomer(customerId)
                    .setType(PaymentMethodListParams.Type.CARD)
                    .build()
            ).data
        } catch (e: StripeException) {
            logger.error("❌ Failed to list payment methods for customer $customerId", e)
            throw RuntimeException("Failed to list payment methods: ${e.message}", e)
        }
    }

    @Transactional
    fun attachPaymentMethod(paymentMethodId: String, customerId: String, user: AppUser): PaymentMethod {
        try {
            val paymentMethod = PaymentMethod.retrieve(paymentMethodId)
            paymentMethod.attach(PaymentMethodAttachParams.builder().setCustomer(customerId).build())

            Customer.retrieve(customerId).update(
                CustomerUpdateParams.builder()
                    .setInvoiceSettings(
                        CustomerUpdateParams.InvoiceSettings.builder()
                            .setDefaultPaymentMethod(paymentMethodId)
                            .build()
                    )
                    .build()
            )

            user.defaultPaymentMethodId = paymentMethodId
            user.updatedAt              = Instant.now()
            userRepository.save(user)
            logger.info("✅ Attached + set default payment method $paymentMethodId for user ${user.id}")
            return paymentMethod
        } catch (e: StripeException) {
            logger.error("❌ Failed to attach payment method $paymentMethodId", e)
            throw RuntimeException("Failed to attach payment method: ${e.message}", e)
        }
    }

    fun detachPaymentMethod(paymentMethodId: String) {
        try {
            PaymentMethod.retrieve(paymentMethodId).detach()
        } catch (e: StripeException) {
            logger.error("❌ Failed to detach payment method $paymentMethodId", e)
            throw RuntimeException("Failed to detach payment method: ${e.message}", e)
        }
    }

    // ── Fee calculation ──────────────────────────────────────────────────────

    /**
     * FIX — Merged duplicate fee methods:
     *   computeApplicationFeeCents() was a private re-implementation of the
     *   same math in calculateFeeBreakdown(), duplicated because the PaymentIntent
     *   path needed a Long (cents) and the controller path needed a FeeBreakdown.
     *   Now there is one method. createPaymentIntentWithSavedMethod() calls
     *   calculateFeeBreakdown() and reads .totalFeeCents directly — no duplicate
     *   logic, no risk of the two drifting apart if fee rates change.
     */
    fun calculateFeeBreakdown(amount: BigDecimal): FeeBreakdown {
        val amountCents = toCents(amount)

        val stripePercentCents = BigDecimal(amountCents)
            .multiply(processingFeePercent)
            .divide(BigDecimal("100"), 10, RoundingMode.HALF_UP)
            .setScale(0, RoundingMode.CEILING)
            .longValueExact()
        val stripeFeeCents = stripePercentCents + processingFeeFixedCents

        val platformPercentCents = BigDecimal(amountCents)
            .multiply(platformFeePercent)
            .divide(BigDecimal("100"), 10, RoundingMode.HALF_UP)
            .setScale(0, RoundingMode.CEILING)
            .longValueExact()
        val platformFeeCents = platformPercentCents + platformFeeFixedCents

        val totalAppFeeCents  = stripeFeeCents + platformFeeCents
        val receiverNetCents  = amountCents - totalAppFeeCents

        return FeeBreakdown(
            senderPaysCents   = amountCents,
            stripeFeeCents    = stripeFeeCents,
            platformFeeCents  = platformFeeCents,
            totalFeeCents     = totalAppFeeCents,
            receiverGetsCents = receiverNetCents,
            senderPays        = cents(amountCents),
            stripeFee         = cents(stripeFeeCents),
            platformFee       = cents(platformFeeCents),
            totalFee          = cents(totalAppFeeCents),
            receiverGets      = cents(receiverNetCents)
        )
    }

    // ── Payment intent ───────────────────────────────────────────────────────

    fun createPaymentIntentWithSavedMethod(
        amount: BigDecimal,
        receiverStripeAccountId: String,
        senderId: UUID,
        receiverId: UUID,
        tipNonce: String,
        paymentMethodId: String,
        senderCustomerId: String
    ): PaymentIntent {
        try {
            val breakdown = calculateFeeBreakdown(amount)   // single source of truth
            val amountCents          = breakdown.senderPaysCents
            val applicationFeeCents  = breakdown.totalFeeCents

            if (amountCents < minimumChargeCents)
                throw IllegalArgumentException("Tip is below minimum charge: $minimumChargeCents cents")
            if (applicationFeeCents >= amountCents)
                throw IllegalArgumentException("Computed fee ($applicationFeeCents) must be less than amount ($amountCents)")

            val paymentIntent = PaymentIntent.create(
                PaymentIntentCreateParams.builder()
                    .setAmount(amountCents)
                    .setCurrency("usd")
                    .setCustomer(senderCustomerId)
                    .setPaymentMethod(paymentMethodId)
                    .setConfirm(true)
                    .setOffSession(true)
                    .setApplicationFeeAmount(applicationFeeCents)
                    .setTransferData(
                        PaymentIntentCreateParams.TransferData.builder()
                            .setDestination(receiverStripeAccountId)
                            .build()
                    )
                    .putMetadata("sender_id",         senderId.toString())
                    .putMetadata("receiver_id",       receiverId.toString())
                    .putMetadata("tip_nonce",         tipNonce)
                    .putMetadata("gross_cents",       amountCents.toString())
                    .putMetadata("app_fee_cents",     applicationFeeCents.toString())
                    .putMetadata("receiver_net_cents", breakdown.receiverGetsCents.toString())
                    .build()
            )
            logger.info("✅ PaymentIntent ${paymentIntent.id} status=${paymentIntent.status}")
            return paymentIntent
        } catch (e: StripeException) {
            logger.error("❌ Failed to create PaymentIntent: ${e.message}", e)
            throw RuntimeException("Failed to create payment intent: ${e.message}", e)
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun toCents(amount: BigDecimal): Long =
        amount.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact()

    private fun cents(c: Long): BigDecimal =
        BigDecimal(c).divide(BigDecimal(100), 2, RoundingMode.HALF_UP)
}

data class FeeBreakdown(
    val senderPaysCents: Long,
    val stripeFeeCents: Long,
    val platformFeeCents: Long,
    val totalFeeCents: Long,
    val receiverGetsCents: Long,
    val senderPays: BigDecimal,
    val stripeFee: BigDecimal,
    val platformFee: BigDecimal,
    val totalFee: BigDecimal,
    val receiverGets: BigDecimal
)