package com.taptaptips.server.domain

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/** Each user's shareable referral code (created the first time they open "Refer a friend"). */
@Entity
@Table(name = "referral_code")
class ReferralCode(
    @Id
    @Column(name = "user_id")
    val userId: UUID,

    @Column(nullable = false, unique = true, length = 12)
    val code: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now()
)

/**
 * Lifecycle of one referral:
 *
 *   PENDING ──new user's tip is charged──▶ QUALIFIED ──payout delay + tip SUCCEEDED──▶ PAYING ──▶ PAID
 *      │                                       │
 *      └─ fraud check fails ─▶ REJECTED        ├─ referrer over monthly cap ─▶ CAPPED (no reward)
 *                                              └─ qualifying tip refunded/disputed ─▶ REJECTED
 *
 * QUALIFIED rows wait until the referrer has a transfer-ready Stripe account
 * and the qualifying tip has reached the receiver, then the scheduler pays them.
 */
enum class ReferralStatus { PENDING, QUALIFIED, PAYING, PAID, REJECTED, CAPPED }

@Entity
@Table(
    name = "referral",
    indexes = [
        Index(name = "idx_referral_referrer_status", columnList = "referrer_id,status"),
        Index(name = "idx_referral_status_qualified", columnList = "status,qualified_at"),
        Index(name = "idx_referral_card_fp", columnList = "card_fingerprint")
    ]
)
class Referral(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "referrer_id", nullable = false)
    val referrerId: UUID,

    /** One referral per new account. */
    @Column(name = "referred_user_id", nullable = false, unique = true)
    val referredUserId: UUID,

    @Column(nullable = false, length = 12)
    val code: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    var status: ReferralStatus = ReferralStatus.PENDING,

    @Column(name = "reward_cents", nullable = false)
    val rewardCents: Long,

    /**
     * Stripe card fingerprint of the card that paid the qualifying tip — same
     * physical card = same value. Partial UNIQUE index over QUALIFIED/PAYING/PAID
     * rows (created by ReferralSchema) makes "one reward per card" race-proof.
     */
    @Column(name = "card_fingerprint", length = 64)
    var cardFingerprint: String? = null,

    /** The tip that qualified this referral. Null on rows qualified under the old card-only rule. */
    @Column(name = "qualifying_tip_id")
    var qualifyingTipId: UUID? = null,

    @Column(name = "reject_reason", length = 100)
    var rejectReason: String? = null,

    @Column(name = "transfer_id")
    var transferId: String? = null,

    @Column(name = "pay_attempts", nullable = false)
    var payAttempts: Int = 0,

    @Column(name = "last_error", length = 1000)
    var lastError: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "qualified_at")
    var qualifiedAt: Instant? = null,

    @Column(name = "paid_at")
    var paidAt: Instant? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
)
