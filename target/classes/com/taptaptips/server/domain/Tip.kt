package com.taptaptips.server.domain

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

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

    val nonce: String = "",

    @Column(name = "timestamp", nullable = false)
    val timestamp: Long = 0,

    val verified: Boolean = false
)
