package com.taptaptips.server.domain

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * What we've seen a user use: the IPs they signed up and logged in from,
 * their normalized email, and their payout bank fingerprints.
 *
 * Kept so a ban can copy everything about the user onto the ban list in one
 * step, and so we can spot one bank account shared by many users.
 * Same `type` values as [BannedIdentifier].
 */
@Entity
@Table(
    name = "user_signal",
    indexes = [
        Index(name = "ux_user_signal_user_type_value", columnList = "user_id,type,value", unique = true),
        Index(name = "idx_user_signal_type_value", columnList = "type,value")
    ]
)
open class UserSignal(
    @Id val id: UUID = UUID.randomUUID(),

    @Column(name = "user_id", nullable = false)
    val userId: UUID = UUID.randomUUID(),

    @Column(nullable = false, length = 16)
    val type: String = "",

    @Column(nullable = false, length = 255)
    val value: String = "",

    @Column(name = "first_seen", nullable = false)
    val firstSeen: Instant = Instant.now(),

    @Column(name = "last_seen", nullable = false)
    var lastSeen: Instant = Instant.now()
)
