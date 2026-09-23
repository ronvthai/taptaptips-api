package com.taptaptips.server.domain

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Lifecycle of a tip whose receiver has NOT finished Stripe Connect onboarding.
 *
 *   AWAITING_PAYMENT ──charge.succeeded──▶ HELD ──receiver onboarded──▶ RELEASING ──▶ RELEASED
 *          │                                 │  │                          │
 *          └──payment failed──▶ FAILED       │  └─dispute/review─▶ BLOCKED │ (transfer error → back to HELD)
 *                                            │                            │
 *                                            └──expired / receiver deleted──▶ REFUNDING ──▶ REFUNDED
 *
 * Every transition out of HELD goes through a conditional UPDATE
 * (claimForRelease / claimForRefund) so two workers — webhook, scheduler,
 * status poll — can never release or refund the same row twice.
 */
enum class HeldTipStatus {
    AWAITING_PAYMENT,
    HELD,
    RELEASING,
    RELEASED,
    REFUNDING,
    REFUNDED,
    BLOCKED,
    FAILED
}

/**
 * Temporary "holding" record for a tip sent to a user who has not linked a
 * payout account yet. The sender's card is charged on the PLATFORM account
 * (Stripe "separate charges and transfers"), the money stays in the platform
 * balance, and a Stripe Transfer to the receiver's connected account is
 * created once they finish onboarding.
 *
 * One row per tip (tip_id is unique). The row deliberately stores sender and
 * receiver as plain UUIDs rather than FKs, so an account deletion can't
 * silently cascade away a money-holding record.
 */
@Entity
@Table(
    name = "held_tip",
    indexes = [
        Index(name = "idx_held_tip_receiver_status", columnList = "receiver_id,status"),
        Index(name = "idx_held_tip_sender_created", columnList = "sender_id,created_at"),
        Index(name = "idx_held_tip_status_expires", columnList = "status,expires_at"),
        Index(name = "idx_held_tip_payment_intent", columnList = "payment_intent_id", unique = true),
        Index(name = "idx_held_tip_charge", columnList = "charge_id", unique = true)
    ]
)
open class HeldTip(
    @Id val id: UUID = UUID.randomUUID(),

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tip_id", nullable = false, unique = true)
    val tip: Tip,

    @Column(name = "sender_id")
    var senderId: UUID? = null,

    @Column(name = "receiver_id", nullable = false)
    val receiverId: UUID,

    // ── Money (integer cents — never floating point) ─────────────────────────
    /** What the sender's card is charged. */
    @Column(name = "gross_cents", nullable = false)
    val grossCents: Long,

    /** What will be transferred to the receiver once they onboard. */
    @Column(name = "net_cents", nullable = false)
    val netCents: Long,

    @Column(name = "platform_fee_cents", nullable = false)
    val platformFeeCents: Long,

    @Column(name = "currency", nullable = false, length = 3)
    val currency: String = "usd",

    // ── Security / audit ─────────────────────────────────────────────────────
    /** Same nonce as the tip row — part of the integrity hash. */
    @Column(name = "nonce", nullable = false)
    val nonce: String,

    /** Device whose Ed25519 key verified the sender's signature. */
    @Column(name = "sender_device_id")
    val senderDeviceId: UUID? = null,

    /**
     * HMAC-SHA256 over the immutable fields (id, tip id, sender, receiver,
     * amounts, nonce, created_at). Re-computed before any money moves; a
     * mismatch means the row was altered outside the app and the tip is
     * BLOCKED for manual review instead of being paid out.
     */
    @Column(name = "integrity_hash", nullable = false, length = 64)
    var integrityHash: String = "",

    // ── Stripe references ────────────────────────────────────────────────────
    @Column(name = "payment_intent_id")
    var paymentIntentId: String? = null,

    @Column(name = "charge_id")
    var chargeId: String? = null,

    @Column(name = "transfer_id")
    var transferId: String? = null,

    @Column(name = "refund_id")
    var refundId: String? = null,

    /** Connected account the funds were released to (snapshot at release time). */
    @Column(name = "destination_account_id")
    var destinationAccountId: String? = null,

    @Column(name = "transfer_group", nullable = false)
    val transferGroup: String,

    // ── State ────────────────────────────────────────────────────────────────
    @Column(name = "status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    var status: HeldTipStatus = HeldTipStatus.AWAITING_PAYMENT,

    @Column(name = "block_reason")
    var blockReason: String? = null,

    @Column(name = "release_attempts", nullable = false)
    var releaseAttempts: Int = 0,

    @Column(name = "last_error", length = 1000)
    var lastError: String? = null,

    // ── Timestamps ───────────────────────────────────────────────────────────
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant,

    @Column(name = "held_at")
    var heldAt: Instant? = null,

    @Column(name = "released_at")
    var releasedAt: Instant? = null,

    @Column(name = "refunded_at")
    var refundedAt: Instant? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0
)
