package com.taptaptips.server.domain

import jakarta.persistence.*
import java.time.Instant
import java.util.*

@Entity
@Table(
    name = "bank_accounts",
    indexes = [Index(columnList = "user_id,is_default")]
)
data class BankAccount(
    @Id val id: UUID = UUID.randomUUID(),

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: AppUser,

    // safe-to-return
    @Column(nullable = false) val last4: String,
    @Column(nullable = false) val accountType: String,            // CHECKING | SAVINGS
    @Column(nullable = true)  val institutionName: String? = null,

    // encrypted blobs (base64 of iv||ciphertext)
    @Column(nullable = false, length = 4096) val routingEnc: String,
    @Column(nullable = false, length = 4096) val accountEnc: String,

    @Column(nullable = false) var isDefault: Boolean = true,
    @Column(nullable = false) var status: String = "VERIFIED",    // or PENDING_VERIFICATION
    @Column(nullable = false) var createdAt: Instant = Instant.now(),
    @Column(nullable = false) var updatedAt: Instant = Instant.now()
)
