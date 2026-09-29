package com.taptaptips.server.domain

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * A display name that matched a REVIEW term. The name was accepted; this row
 * is your queue to look at later. Set reviewed = true once handled.
 */
@Entity
@Table(
    name = "display_name_flag",
    indexes = [Index(name = "idx_display_name_flag_reviewed", columnList = "reviewed,created_at")]
)
class DisplayNameFlag(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "user_id", nullable = false)
    val userId: UUID,

    @Column(name = "display_name", nullable = false, length = 100)
    val displayName: String,

    @Column(name = "matched_term", nullable = false, length = 100)
    val matchedTerm: String,

    @Column(nullable = false)
    var reviewed: Boolean = false,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now()
)
