package com.taptaptips.server.domain

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.time.LocalDate

// Add this enum at the top of the file
enum class TipStatus {
    PENDING,
    SUCCEEDED,
    FAILED,
    DISPUTED,
    REFUNDED
}

@Entity
@Table(name = "tip")
open class Tip(
    @Id val id: UUID = UUID.randomUUID(),

    @ManyToOne @JoinColumn(name = "sender_id")
    val sender: AppUser? = null,

    @ManyToOne @JoinColumn(name = "receiver_id")
    val receiver: AppUser? = null,

    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    val amount: BigDecimal = BigDecimal.ZERO,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "created_at_local", nullable = false)  // ⭐ NEW
    val createdAtLocal: LocalDate = LocalDate.now(),      // ⭐ NEW

    @Column(name = "timezone", nullable = false, length = 50)  // ⭐ NEW
    val timezone: String = "",                                  // ⭐ NEW

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
