package com.taptaptips.server.domain

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "password_reset_token")
class PasswordResetToken(
    @Id
    val id: UUID = UUID.randomUUID(),
    
    @Column(nullable = false, unique = true)
    val token: String = UUID.randomUUID().toString(),
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: AppUser,
    
    @Column(nullable = false, name = "expires_at")
    val expiresAt: Instant,
    
    @Column(nullable = false, name = "created_at")
    val createdAt: Instant = Instant.now(),
    
    @Column(nullable = false)
    var used: Boolean = false,
    
    @Column(name = "used_at")
    var usedAt: Instant? = null
) {
    fun isValid(): Boolean {
        return !used && Instant.now().isBefore(expiresAt)
    }
}