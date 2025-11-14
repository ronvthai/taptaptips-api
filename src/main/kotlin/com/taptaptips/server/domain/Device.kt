package com.taptaptips.server.domain

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "device",
    indexes = [
        Index(name = "idx_device_user", columnList = "user_id"),
        Index(name = "idx_device_user_pubkey", columnList = "user_id,public_key"),
        Index(name = "idx_device_user_active", columnList = "user_id,is_active")  // NEW
    ]
)
open class Device(
    @Id 
    val id: UUID = UUID.randomUUID(),
    
    @ManyToOne(optional = false) 
    @JoinColumn(name = "user_id") 
    val user: AppUser? = null,
    
    val platform: String = "android",
    
    @Column(name = "device_name") 
    var deviceName: String? = null,
    
    @Column(name = "public_key", columnDefinition = "BYTEA") 
    val publicKey: ByteArray = ByteArray(0),
    
    @Column(name = "last_seen") 
    var lastSeen: Instant? = null,
    
    @Column(name = "created_at") 
    val createdAt: Instant = Instant.now(),
    
    @Column(name = "updated_at")
    var updatedAt: Instant = Instant.now(),
    
    @Column(name = "is_active")  // NEW FIELD
    var isActive: Boolean = true  // NEW FIELD
) {
    // Override equals and hashCode for proper entity comparison
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Device) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
    
    // Don't include publicKey in toString to avoid huge logs
    override fun toString(): String {
        return "Device(id=$id, deviceName=$deviceName, platform=$platform, user=${user?.id})"
    }
}
