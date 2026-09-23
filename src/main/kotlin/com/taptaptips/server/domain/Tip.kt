package com.taptaptips.server.domain

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.time.LocalDate

enum class TipStatus {
    PENDING,
    /** Card charged, money parked on the platform until the receiver links a bank (see HeldTip). */
    HELD,
    SUCCEEDED,
    FAILED,
    DISPUTED,
    REFUNDED
}

@Entity
@Table(
    name = "tip",
    indexes = [
        // Hit on every tip send — existsBySender_IdAndNonce() is the
        // idempotency check that runs before any Stripe call is made.
        Index(name = "idx_tip_sender_nonce", columnList = "sender_id,nonce"),

        // Hit on every Stripe webhook event. Unique because a duplicate
        // here means Stripe sent us a payment_intent/charge id collision,
        // which should never happen and would indicate a real bug — a
        // unique index catches that at the DB level instead of silently
        // updating the wrong tip. Postgres unique indexes allow multiple
        // NULLs, so this doesn't block PENDING tips that don't have a
        // payment_intent_id / charge_id yet.
        Index(name = "idx_tip_payment_intent_id", columnList = "payment_intent_id", unique = true),
        Index(name = "idx_tip_charge_id", columnList = "charge_id", unique = true),

        // Hit on every "received"/"sent" history page load (paginated,
        // fetch-joined queries in TipRepository).
        Index(name = "idx_tip_receiver_created_local", columnList = "receiver_id,created_at_local"),
        Index(name = "idx_tip_sender_created_local", columnList = "sender_id,created_at_local")
    ]
)
open class Tip(
    @Id val id: UUID = UUID.randomUUID(),

    @ManyToOne @JoinColumn(name = "sender_id")
    val sender: AppUser? = null,

    @ManyToOne @JoinColumn(name = "receiver_id")
    val receiver: AppUser? = null,

    // ⭐ GROSS AMOUNT - What sender paid
    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    val amount: BigDecimal = BigDecimal.ZERO,

    // ⭐ NEW: NET AMOUNT - What receiver actually gets after fees
    @Column(name = "net_amount", nullable = true, precision = 18, scale = 2)
    val netAmount: BigDecimal? = null,

    // ⭐ NEW: TOTAL FEES - Stripe + Platform fees combined
    @Column(name = "total_fees", nullable = true, precision = 18, scale = 2)
    val totalFees: BigDecimal? = null,

    // ⭐ NEW: PLATFORM FEE - Your 3% revenue
    @Column(name = "platform_fee", nullable = true, precision = 18, scale = 2)
    val platformFee: BigDecimal? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "created_at_local", nullable = false)
    val createdAtLocal: LocalDate = LocalDate.now(),

    @Column(name = "timezone", nullable = false, length = 50)
    val timezone: String = "",

    val nonce: String = "",

    @Column(name = "timestamp", nullable = false)
    val timestamp: Long = 0,

    val verified: Boolean = false,
    
    @Column(name = "status")
    @Enumerated(EnumType.STRING)
    var status: TipStatus = TipStatus.PENDING,

    @Column(name = "payment_intent_id")
    var paymentIntentId: String? = null,

    @Column(name = "charge_id")
    var chargeId: String? = null,

    @Column(name = "dispute_id")
    var disputeId: String? = null,

    @Column(name = "dispute_reason")
    var disputeReason: String? = null,

    @Column(name = "fraud_warning")
    var fraudWarning: Boolean = false,

    @Column(name = "fraud_type")
    var fraudType: String? = null,

    @Column(name = "failure_reason")
    var failureReason: String? = null,

    @Column(name = "updated_at")
    var updatedAt: Instant? = null
)