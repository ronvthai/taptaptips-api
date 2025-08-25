package com.taptaptips.server.domain

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "device")
open class Device(
    @Id val id: UUID = UUID.randomUUID(),
    @ManyToOne(optional = false) @JoinColumn(name = "user_id") val user: AppUser? = null,
    val platform: String = "android",
    @Column(name = "device_name") val deviceName: String? = null,
    @Column(name = "public_key", columnDefinition = "BYTEA") val publicKey: ByteArray = ByteArray(0),
    @Column(name = "last_seen") var lastSeen: Instant? = null,
    @Column(name = "created_at") val createdAt: Instant = Instant.now()
)