package com.taptaptips.server.domain

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * A tip paid on the public web page (tip.taptaptips.com/t/CODE) by someone
 * WITHOUT the app — Apple Pay / Google Pay / card, no account.
 *
 * Tracks the Stripe PaymentIntent until it succeeds. Only then is a normal
 * [Tip] row created (sender = null), so abandoned checkouts never show up in
 * the receiver's history.
 *
 * status: PENDING → SUCCEEDED, FAILED (card declined or blocked by Radar;
 * the PaymentIntent is canceled so it can't be retried with another card),
 * or ABANDONED (never paid within 24h).
 */
@Entity
@Table(
    name = "guest_tip",
    indexes = [
        Index(name = "ux_guest_tip_pi", columnList = "payment_intent_id", unique = true),
        Index(name = "idx_guest_tip_status_created", columnList = "status,created_at")
    ]
)
open class GuestTip(
    @Id val id: UUID = UUID.randomUUID(),

    @Column(name = "payment_intent_id", nullable = false, length = 64)
    val paymentIntentId: String = "",

    @Column(name = "receiver_id", nullable = false)
    val receiverId: UUID = UUID.randomUUID(),

    /** What the tipper paid. */
    @Column(name = "amount_cents", nullable = false)
    val amountCents: Long = 0,

    /** What the receiver gets after Stripe + platform fees. */
    @Column(name = "net_cents", nullable = false)
    val netCents: Long = 0,

    @Column(name = "status", nullable = false, length = 16)
    var status: String = "PENDING",

    /** The Tip row created once paid. */
    @Column(name = "tip_id")
    var tipId: UUID? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "completed_at")
    var completedAt: Instant? = null
)
