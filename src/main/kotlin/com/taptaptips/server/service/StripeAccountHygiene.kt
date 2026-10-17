package com.taptaptips.server.service

import com.stripe.exception.StripeException
import com.stripe.model.Account
import com.stripe.param.AccountUpdateParams
import com.taptaptips.server.repo.AppUserRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

/**
 * Keeps every receiver's Stripe account coded as a normal PURCHASE.
 *
 * Why: tips are charged on_behalf_of the receiver, so the receiver's MCC is
 * what the tipper's card bank sees. MCC 4829 (Money Orders / Wire Transfers)
 * — which Stripe gave some older accounts — makes banks treat a tip as a
 * CASH ADVANCE: a fee (often $10) plus cash-advance interest for the tipper.
 *
 * Fixes, for every receiver account:
 *   - MCC → 7299 (Miscellaneous Personal Services)
 *   - Statement descriptor → "TAPTAPTIPS" (card shows "TAPTAPTIPS* TIP",
 *     not a stranger's name that looks like a person-to-person transfer)
 *
 * Runs at startup and every hour (catches accounts whose MCC changes during
 * Stripe onboarding). Only calls Stripe's update when something is wrong.
 *
 * Config: stripe.accountHygiene.enabled (default true)
 */
@Service
class StripeAccountHygiene(
    private val users: AppUserRepository,
    @Value("\${stripe.accountHygiene.enabled:true}") private val enabled: Boolean
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val MCC = "7299"
        const val DESCRIPTOR = "TAPTAPTIPS"
        /** Card-network codes treated as cash / cash-like. */
        val CASH_LIKE_MCCS = setOf("4829", "6010", "6011", "6050", "6051", "6540", "7995")
    }

    @EventListener(ApplicationReadyEvent::class)
    fun onStartup() = runSafely()

    @Scheduled(fixedDelayString = "\${stripe.accountHygiene.intervalMs:3600000}",
               initialDelayString = "\${stripe.accountHygiene.intervalMs:3600000}")
    fun scheduled() = runSafely()

    private fun runSafely() {
        if (!enabled) return
        try { fixAll() } catch (e: Exception) { log.error("❌ Stripe account hygiene run failed", e) }
    }

    fun fixAll() {
        val accountIds = users.findAll().mapNotNull { it.stripeAccountId }.distinct()
        var fixed = 0
        accountIds.forEach { if (fixOne(it)) fixed++ }
        log.info("🧾 Stripe account hygiene: checked ${accountIds.size}, fixed $fixed")
    }

    /** Returns true if the account was changed. Never throws. */
    fun fixOne(accountId: String): Boolean {
        return try {
            val account = Account.retrieve(accountId)
            val currentMcc = account.businessProfile?.mcc
            val currentDescriptor = account.settings?.payments?.statementDescriptor

            val needsMcc = currentMcc != MCC
            val needsDescriptor = currentDescriptor != DESCRIPTOR
            if (!needsMcc && !needsDescriptor) return false

            if (currentMcc in CASH_LIKE_MCCS) {
                log.warn("⚠️ $accountId has cash-like MCC $currentMcc — tips were coded as cash advances. Fixing.")
            }

            var changed = false
            // Separate calls so one rejected field doesn't block the other.
            if (needsMcc) {
                try {
                    account.update(
                        AccountUpdateParams.builder()
                            .setBusinessProfile(
                                AccountUpdateParams.BusinessProfile.builder().setMcc(MCC).build()
                            ).build()
                    )
                    log.info("✅ $accountId MCC $currentMcc → $MCC")
                    changed = true
                } catch (e: StripeException) {
                    log.error("❌ $accountId MCC update rejected: ${e.message} — set it in the Stripe Dashboard")
                }
            }
            if (needsDescriptor) {
                try {
                    Account.retrieve(accountId).update(
                        AccountUpdateParams.builder()
                            .setSettings(
                                AccountUpdateParams.Settings.builder()
                                    .setPayments(
                                        AccountUpdateParams.Settings.Payments.builder()
                                            .setStatementDescriptor(DESCRIPTOR).build()
                                    ).build()
                            ).build()
                    )
                    log.info("✅ $accountId statement descriptor '$currentDescriptor' → '$DESCRIPTOR'")
                    changed = true
                } catch (e: StripeException) {
                    log.error("❌ $accountId descriptor update rejected: ${e.message}")
                }
            }
            changed
        } catch (e: Exception) {
            log.error("❌ Could not check Stripe account $accountId", e)
            false
        }
    }
}
