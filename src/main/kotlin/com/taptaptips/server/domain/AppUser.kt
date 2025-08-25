package com.taptaptips.server.domain

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "app_user")
open class AppUser(
    @Id val id: UUID = UUID.randomUUID(),

    @Column(nullable = false, unique = true, columnDefinition = "citext")
    val email: String = "",

    @Column(nullable = false, name = "password_hash")
    val passwordHash: String = "",

    @Column(nullable = false, name = "display_name")
    val displayName: String = "",

    @Column(nullable = false, name = "created_at")
    val createdAt: Instant = Instant.now(),

    @Column(nullable = false, name = "updated_at")
    var updatedAt: Instant = Instant.now(),
    @Basic(fetch = FetchType.LAZY)
    @Column(name = "profile_picture", columnDefinition = "bytea")
    var profilePicture: ByteArray? = null
)
