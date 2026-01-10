package com.taptaptips.server.web

import com.stripe.exception.SignatureVerificationException
import com.stripe.model.*
import com.stripe.net.Webhook
import com.taptaptips.server.domain.TipStatus
import com.taptaptips.server.repo.AppUserRepository
import com.taptaptips.server.repo.TipRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/webhooks/stripe")
class StripeWebhookController(
    private val tipRepository: TipRepository,
    private val userRepository: AppUserRepository,
    
    @Value("\${stripe.webhook.secret}")
    private val webhookSecret: String
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    
    @PostMapping
    fun handleWebhook(
        @RequestBody payload: String,
        @RequestHeader("Stripe-Signature") signature: String
    ): ResponseEntity<String> {
        
        logger.info("📥 Received webhook request")
        
        val event = try {
            Webhook.constructEvent(payload, signature, webhookSecret)
        } catch (e: SignatureVerificationException) {
            logger.error("❌ Invalid webhook signature")
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid signature")
        } catch (e: Exception) {
            logger.error("❌ Webhook parsing failed", e)
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Parsing error")
        }
        
        logger.info("✅ Webhook verified: ${event.type}")
        
        try {
            when (event.type) {
                "charge.dispute.created" -> handleDisputeCreated(event)
                "charge.dispute.updated" -> handleDisputeUpdated(event)
                "charge.dispute.closed" -> handleDisputeClosed(event)
                "charge.succeeded" -> handleChargeSucceeded(event)
                "charge.refunded" -> handleChargeRefunded(event)
                "payment_intent.payment_failed" -> handlePaymentFailed(event)
                "payment_intent.canceled" -> handlePaymentCanceled(event)
                "radar.early_fraud_warning.created" -> handleFraudWarning(event)
                "review.opened" -> handleReviewOpened(event)
                else -> logger.info("⚠️ Unhandled webhook type: ${event.type}")
            }
        } catch (e: Exception) {
            logger.error("❌ Error processing webhook ${event.type}", e)
        }
        
        return ResponseEntity.ok("Received")
    }
    
    private fun handleDisputeCreated(event: Event) {
        val dispute = event.dataObjectDeserializer.`object`.get() as Dispute
        val paymentIntentId = dispute.paymentIntent
        
        logger.error("🚨 DISPUTE CREATED")
        logger.error("   Dispute ID: ${dispute.id}")
        logger.error("   Amount: ${dispute.amount} cents")
        logger.error("   Reason: ${dispute.reason}")
        logger.error("   Payment Intent: $paymentIntentId")
        
        val tip = tipRepository.findByPaymentIntentId(paymentIntentId)
        
        if (tip != null) {
            tip.status = TipStatus.DISPUTED
            tip.disputeId = dispute.id
            tip.disputeReason = dispute.reason
            tip.updatedAt = Instant.now()
            tipRepository.save(tip)
            
            logger.info("✅ Marked tip ${tip.id} as DISPUTED")
            
            tip.sender?.id?.let { senderId ->
                checkSenderForFraud(senderId)
            }
        } else {
            logger.warn("⚠️ Could not find tip for payment intent: $paymentIntentId (likely a test event)")
        }
    }
    
    private fun handleDisputeUpdated(event: Event) {
        val dispute = event.dataObjectDeserializer.`object`.get() as Dispute
        logger.info("📝 Dispute updated: ${dispute.id}")
        
        val tip = tipRepository.findByPaymentIntentId(dispute.paymentIntent)
        if (tip != null) {
            tip.disputeReason = dispute.reason
            tip.updatedAt = Instant.now()
            tipRepository.save(tip)
            logger.info("✅ Updated dispute for tip ${tip.id}")
        } else {
            logger.warn("⚠️ Could not find tip for payment intent: ${dispute.paymentIntent}")
        }
    }
    
    private fun handleDisputeClosed(event: Event) {
        val dispute = event.dataObjectDeserializer.`object`.get() as Dispute
        logger.info("✅ Dispute closed: ${dispute.id} - Status: ${dispute.status}")
        
        val tip = tipRepository.findByPaymentIntentId(dispute.paymentIntent)
        if (tip != null) {
            tip.status = if (dispute.status == "won") TipStatus.SUCCEEDED else TipStatus.REFUNDED
            tip.updatedAt = Instant.now()
            tipRepository.save(tip)
            logger.info("✅ Closed dispute for tip ${tip.id}, status: ${tip.status}")
        } else {
            logger.warn("⚠️ Could not find tip for payment intent: ${dispute.paymentIntent}")
        }
    }
    
    private fun handleChargeSucceeded(event: Event) {
        val charge = event.dataObjectDeserializer.`object`.get() as Charge
        logger.info("✅ Charge succeeded: ${charge.id}")
        
        val tip = tipRepository.findByPaymentIntentId(charge.paymentIntent)
        if (tip != null) {
            tip.status = TipStatus.SUCCEEDED
            tip.chargeId = charge.id
            tip.updatedAt = Instant.now()
            tipRepository.save(tip)
            logger.info("✅ Marked tip ${tip.id} as SUCCEEDED")
        } else {
            logger.warn("⚠️ Could not find tip for payment intent: ${charge.paymentIntent} (likely a test event)")
        }
    }
    
    private fun handleChargeRefunded(event: Event) {
        val charge = event.dataObjectDeserializer.`object`.get() as Charge
        logger.warn("💰 Charge refunded: ${charge.id}")
        
        val tip = tipRepository.findByPaymentIntentId(charge.paymentIntent)
        if (tip != null) {
            tip.status = TipStatus.REFUNDED
            tip.updatedAt = Instant.now()
            tipRepository.save(tip)
            logger.info("✅ Marked tip ${tip.id} as REFUNDED")
        } else {
            logger.warn("⚠️ Could not find tip for payment intent: ${charge.paymentIntent}")
        }
    }
    
    private fun handlePaymentFailed(event: Event) {
        val paymentIntent = event.dataObjectDeserializer.`object`.get() as PaymentIntent
        val tipNonce = paymentIntent.metadata["tip_nonce"]
        
        logger.error("❌ Payment failed: ${paymentIntent.id}")
        
        if (tipNonce != null) {
            val tip = tipRepository.findByNonce(tipNonce)
            if (tip != null) {
                tip.status = TipStatus.FAILED
                tip.failureReason = paymentIntent.lastPaymentError?.message
                tip.updatedAt = Instant.now()
                tipRepository.save(tip)
                logger.info("✅ Marked tip ${tip.id} as FAILED")
            } else {
                logger.warn("⚠️ Could not find tip for nonce: $tipNonce (likely a test event)")
            }
        } else {
            logger.warn("⚠️ Payment failed but no tip_nonce in metadata")
        }
    }
    
    private fun handlePaymentCanceled(event: Event) {
        val paymentIntent = event.dataObjectDeserializer.`object`.get() as PaymentIntent
        logger.warn("⚠️ Payment canceled: ${paymentIntent.id}")
        
        val tipNonce = paymentIntent.metadata["tip_nonce"]
        if (tipNonce != null) {
            val tip = tipRepository.findByNonce(tipNonce)
            if (tip != null) {
                tip.status = TipStatus.FAILED
                tip.failureReason = "Payment canceled"
                tip.updatedAt = Instant.now()
                tipRepository.save(tip)
                logger.info("✅ Marked tip ${tip.id} as FAILED (canceled)")
            } else {
                logger.warn("⚠️ Could not find tip for nonce: $tipNonce (likely a test event)")
            }
        } else {
            logger.warn("⚠️ Payment canceled but no tip_nonce in metadata")
        }
    }
    
    private fun handleFraudWarning(event: Event) {
        // EarlyFraudWarning class doesn't exist in this Stripe SDK version
        // Just log it for manual review
        logger.error("🚨🚨🚨 FRAUD WARNING RECEIVED 🚨🚨🚨")
        logger.error("   Event ID: ${event.id}")
        logger.error("   Event Type: ${event.type}")
        logger.error("   ⚠️ Manual review required - check Stripe Dashboard")
        
        // Note: Fraud warnings are extremely rare
        // When they occur, check Stripe Dashboard → Radar → Reviews
        // and manually suspend the user if needed
    }
    
    private fun handleReviewOpened(event: Event) {
        val review = event.dataObjectDeserializer.`object`.get() as Review
        logger.warn("⚠️ Review opened: ${review.id} - Reason: ${review.reason}")
        
        val tip = tipRepository.findByChargeId(review.charge)
        if (tip != null) {
            tip.fraudWarning = true
            tip.fraudType = "stripe_review_${review.reason}"
            tip.updatedAt = Instant.now()
            tipRepository.save(tip)
            logger.info("✅ Flagged tip ${tip.id} for review")
        } else {
            logger.warn("⚠️ Could not find tip for charge: ${review.charge} (likely a test event)")
        }
    }
    
    private fun checkSenderForFraud(senderId: UUID) {
        val disputes = tipRepository.countDisputesBySender(senderId)
        val totalTips = tipRepository.countBySender_Id(senderId)
        
        logger.info("🔍 Checking sender $senderId: $disputes disputes in $totalTips tips")
        
        if (disputes >= 3) {
            suspendUserForFraud(senderId, "High dispute count: $disputes disputes")
        } else if (totalTips > 0 && disputes.toFloat() / totalTips > 0.2) {
            suspendUserForFraud(senderId, "High dispute rate: $disputes/$totalTips")
        }
    }
    
    private fun suspendUserForFraud(userId: UUID, reason: String) {
        val user = userRepository.findById(userId).orElse(null) ?: return
        
        if (user.suspended) {
            logger.info("⚠️ User $userId already suspended")
            return
        }
        
        user.suspended = true
        user.suspensionReason = reason
        user.suspendedAt = Instant.now()
        user.updatedAt = Instant.now()
        userRepository.save(user)
        
        logger.error("🚫 SUSPENDED USER: $userId - $reason")
    }
}