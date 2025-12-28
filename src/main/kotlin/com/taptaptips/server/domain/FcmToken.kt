package com.taptaptips.server.domain

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Stores FCM (Firebase Cloud Messaging) tokens for push notifications.
 * Supports both Android and iOS devices.
 */
@Entity
@Table(
    name = "fcm_token",
    indexes = [
        Index(name = "idx_fcm_user", columnList = "user_id"),
        Index(name = "idx_fcm_token", columnList = "token", unique = true)
    ]
)
open class FcmToken(
    @Id 
    val id: UUID = UUID.randomUUID(),
    
    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id")
    val user: AppUser,
    
    @Column(name = "token", nullable = false, unique = true, length = 500)
    var token: String,
    
    @Column(name = "platform", nullable = false)
    var platform: String = "android",  // 'android' or 'ios'
    
    @Column(name = "device_info")
    var deviceInfo: String? = null,  // Optional: device model, OS version, etc.
    
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
    
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
    
    @Column(name = "last_used")
    var lastUsed: Instant? = null,  // Track when this token last received a notification
    
    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true  // Disable tokens that fail to deliver
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FcmToken) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
    
    override fun toString(): String {
        return "FcmToken(id=$id, userId=${user.id}, platform=$platform, isActive=$isActive)"
    }
}
