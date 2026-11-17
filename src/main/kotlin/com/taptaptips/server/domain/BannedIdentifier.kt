package com.taptaptips.server.domain

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Something tied to a banned user that a new account can be matched against.
 *
 * type:
 *  - BANK  Stripe fingerprint of a payout bank account or debit card. The same
 *          real account gives the same fingerprint on every connected account.
 *          A match suspends the new account and pauses its payouts.
 *  - EMAIL Normalized email (lowercase, Gmail dots removed, "+anything" removed).
 *          A match refuses the sign-up.
 *  - IP    An address the banned user signed up or logged in from. Shared Wi-Fi
 *          and phone carriers put innocent people on one address, so a match
 *          only alerts (unless "Suspend sign-ups from banned addresses" is on).
 */
@Entity
@Table(
    name = "banned_identifier",
    indexes = [Index(name = "ux_banned_identifier_type_value", columnList = "type,value", unique = true)]
)
open class BannedIdentifier(
    @Id val id: UUID = UUID.randomUUID(),

    @Column(nullable = false, length = 16)
    val type: String = "",

    @Column(nullable = false, length = 255)
    val value: String = "",

    /** The banned user this came from, if any. */
    @Column(name = "source_user_id")
    val sourceUserId: UUID? = null,

    @Column(length = 255)
    val reason: String? = null,

    @Column(name = "created_by", length = 128)
    val createdBy: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now()
)
