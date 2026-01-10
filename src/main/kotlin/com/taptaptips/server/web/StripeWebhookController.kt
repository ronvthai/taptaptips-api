package com.taptaptips.server.web

import com.stripe.Stripe
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

    /**
     * Stripe can refuse "safe" deserialization when the Event's api_version doesn't match
     * Stripe.API_VERSION for your SDK. In that case, dataObjectDeserializer.object is empty.
     * This helper prevents Optional.get() crashes and logs the mismatch.
     */
    private fun extractStripeObject(event: Event): StripeObject? {
        val deser = event.dataObjectDeserializer
        val opt = deser.`object`

        if (opt.isPresent) return opt.get()

        logger.warn(
            "⚠️ Stripe event not safely deserializable. type={}, eventId={}, eventApiVersion={}, libraryApiVersion={}",
            event.type,
            event.id,
            event.apiVersion,
            Stripe.API_VERSION
        )

        return try {
            deser.deserializeUnsafe()
        } catch (e: Exception) {
            logger.error(
                "❌ deserializeUnsafe() failed. type={}, eventId={}, rawJson={}",
                event.type,
                event.id,
                deser.rawJson,
                e
            )
            null
        }
    }

    private fun handleDisputeCreated(event: Event) {
        val obj = extractStripeObject(event) ?: return
        val dispute = obj as? Dispute ?: run {
            logger.warn("⚠️ charge.dispute.created but data.object is ${obj.javaClass.name}")
            return
        }

        val paymentIntentId = dispute.paymentIntent
        val chargeId = dispute.charge

        logger.error("🚨 DISPUTE CREATED")
        logger.error("   Dispute ID: ${dispute.id}")
        logger.error("   Amount: ${dispute.amount} cents")
        logger.error("   Reason: ${dispute.reason}")
        logger.error("   Payment Intent: $paymentIntentId")
        logger.error("   Charge: $chargeId")

        val tip = when {
            !paymentIntentId.isNullOrBlank() -> tipRepository.findByPaymentIntentId(paymentIntentId)
            !chargeId.isNullOrBlank() -> tipRepository.findByChargeId(chargeId)
            else -> null
        }

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
            logger.warn("⚠️ Could not find tip for dispute (paymentIntent=$paymentIntentId, charge=$chargeId) (likely fixture/test)")
        }
    }

    private fun handleDisputeUpdated(event: Event) {
        val obj = extractStripeObject(event) ?: return
        val dispute = obj as? Dispute ?: run {
            logger.warn("⚠️ charge.dispute.updated but data.object is ${obj.javaClass.name}")
            return
        }

        logger.info("📝 Dispute updated: ${dispute.id}")

        val paymentIntentId = dispute.paymentIntent
        val chargeId = dispute.charge

        val tip = when {
            !paymentIntentId.isNullOrBlank() -> tipRepository.findByPaymentIntentId(paymentIntentId)
            !chargeId.isNullOrBlank() -> tipRepository.findByChargeId(chargeId)
            else -> null
        }

        if (tip != null) {
            tip.disputeReason = dispute.reason
            tip.updatedAt = Instant.now()
            tipRepository.save(tip)
            logger.info("✅ Updated dispute for tip ${tip.id}")
        } else {
            logger.warn("⚠️ Could not find tip for dispute update (paymentIntent=$paymentIntentId, charge=$chargeId)")
        }
    }

    private fun handleDisputeClosed(event: Event) {
        val obj = extractStripeObject(event) ?: return
        val dispute = obj as? Dispute ?: run {
            logger.warn("⚠️ charge.dispute.closed but data.object is ${obj.javaClass.name}")
            return
        }

        logger.info("✅ Dispute closed: ${dispute.id} - Status: ${dispute.status}")

        val paymentIntentId = dispute.paymentIntent
        val chargeId = dispute.charge

        val tip = when {
            !paymentIntentId.isNullOrBlank() -> tipRepository.findByPaymentIntentId(paymentIntentId)
            !chargeId.isNullOrBlank() -> tipRepository.findByChargeId(chargeId)
            else -> null
        }

        if (tip != null) {
            tip.status = if (dispute.status == "won") TipStatus.SUCCEEDED else TipStatus.REFUNDED
            tip.updatedAt = Instant.now()
            tipRepository.save(tip)
            logger.info("✅ Closed dispute for tip ${tip.id}, status: ${tip.status}")
        } else {
            logger.warn("⚠️ Could not find tip for dispute close (paymentIntent=$paymentIntentId, charge=$chargeId)")
        }
    }

    private fun handleChargeSucceeded(event: Event) {
        val obj = extractStripeObject(event) ?: return
        val charge = obj as? Charge ?: run {
            logger.warn("⚠️ charge.succeeded but data.object is ${obj.javaClass.name}")
            return
        }

        logger.info("✅ Charge succeeded: ${charge.id}")

        val paymentIntentId = charge.paymentIntent
        if (paymentIntentId.isNullOrBlank()) {
            logger.warn("⚠️ Charge ${charge.id} has no payment_intent; cannot map to tip by paymentIntentId")
            // Optional fallback: match by charge id if you already store it
            val tipByCharge = tipRepository.findByChargeId(charge.id)
            if (tipByCharge != null) {
                tipByCharge.status = TipStatus.SUCCEEDED
                tipByCharge.chargeId = charge.id
                tipByCharge.updatedAt = Instant.now()
                tipRepository.save(tipByCharge)
                logger.info("✅ Marked tip ${tipByCharge.id} as SUCCEEDED (matched by chargeId)")
            }
            return
        }

        val tip = tipRepository.findByPaymentIntentId(paymentIntentId)
        if (tip != null) {
            tip.status = TipStatus.SUCCEEDED
            tip.chargeId = charge.id
            tip.updatedAt = Instant.now()
            tipRepository.save(tip)
            logger.info("✅ Marked tip ${tip.id} as SUCCEEDED")
        } else {
            logger.warn("⚠️ Could not find tip for payment intent: $paymentIntentId (likely fixture/test)")
        }
    }

    private fun handleChargeRefunded(event: Event) {
        val obj = extractStripeObject(event) ?: return
        val charge = obj as? Charge ?: run {
            logger.warn("⚠️ charge.refunded but data.object is ${obj.javaClass.name}")
            return
        }

        logger.warn("💰 Charge refunded: ${charge.id}")

        val paymentIntentId = charge.paymentIntent
        val tip = when {
            !paymentIntentId.isNullOrBlank() -> tipRepository.findByPaymentIntentId(paymentIntentId)
            else -> tipRepository.findByChargeId(charge.id)
        }

        if (tip != null) {
            tip.status = TipStatus.REFUNDED
            tip.updatedAt = Instant.now()
            tipRepository.save(tip)
            logger.info("✅ Marked tip ${tip.id} as REFUNDED")
        } else {
            logger.warn("⚠️ Could not find tip for refund (paymentIntent=$paymentIntentId, charge=${charge.id})")
        }
    }

    private fun handlePaymentFailed(event: Event) {
        val obj = extractStripeObject(event) ?: return
        val paymentIntent = obj as? PaymentIntent ?: run {
            logger.warn("⚠️ payment_intent.payment_failed but data.object is ${obj.javaClass.name}")
            return
        }

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
                logger.warn("⚠️ Could not find tip for nonce: $tipNonce (likely fixture/test)")
            }
        } else {
            logger.warn("⚠️ Payment failed but no tip_nonce in metadata (paymentIntent=${paymentIntent.id})")
        }
    }

    private fun handlePaymentCanceled(event: Event) {
        val obj = extractStripeObject(event) ?: return
        val paymentIntent = obj as? PaymentIntent ?: run {
            logger.warn("⚠️ payment_intent.canceled but data.object is ${obj.javaClass.name}")
            return
        }

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
                logger.warn("⚠️ Could not find tip for nonce: $tipNonce (likely fixture/test)")
            }
        } else {
            logger.warn("⚠️ Payment canceled but no tip_nonce in metadata (paymentIntent=${paymentIntent.id})")
        }
    }

    private fun handleFraudWarning(event: Event) {
        // Some Stripe SDK versions may not have a model for EarlyFraudWarning.
        // Log for manual review.
        logger.error("🚨🚨🚨 FRAUD WARNING RECEIVED 🚨🚨🚨")
        logger.error("   Event ID: ${event.id}")
        logger.error("   Event Type: ${event.type}")
        logger.error("   ⚠️ Manual review required - check Stripe Dashboard")
    }

    private fun handleReviewOpened(event: Event) {
        val obj = extractStripeObject(event) ?: return
        val review = obj as? Review ?: run {
            logger.warn("⚠️ review.opened but data.object is ${obj.javaClass.name}")
            return
        }

        logger.warn("⚠️ Review opened: ${review.id} - Reason: ${review.reason}")

        val chargeId = review.charge
        if (chargeId.isNullOrBlank()) {
            logger.warn("⚠️ Review ${review.id} has no charge id; cannot map to tip")
            return
        }

        val tip = tipRepository.findByChargeId(chargeId)
        if (tip != null) {
            tip.fraudWarning = true
            tip.fraudType = "stripe_review_${review.reason}"
            tip.updatedAt = Instant.now()
            tipRepository.save(tip)
            logger.info("✅ Flagged tip ${tip.id} for review")
        } else {
            logger.warn("⚠️ Could not find tip for charge: $chargeId (likely fixture/test)")
        }
    }

    private fun checkSenderForFraud(senderId: UUID) {
        val disputes = tipRepository.countDisputesBySender(senderId)
        val totalTips = tipRepository.countBySender_Id(senderId)

        logger.info("🔍 Checking sender $senderId: $disputes disputes in $totalTips tips")

        if (disputes >= 3) {
            suspendUserForFraud(senderId, "High dispute count: $disputes disputes")
        } else if (totalTips > 0 && disputes.toFloat() / totalTips > 0.2f) {
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
