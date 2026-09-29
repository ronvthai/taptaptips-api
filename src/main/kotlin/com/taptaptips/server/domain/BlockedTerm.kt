package com.taptaptips.server.domain

import jakarta.persistence.*
import java.time.Instant

/**
 * One entry in the display-name word filter. Edit rows in pgAdmin to change
 * the filter — the server reloads the list every 5 minutes, no deploy needed.
 */
enum class TermAction {
    /** Reject the name. */
    BLOCK,
    /** Accept the name but record it in display_name_flag for you to review. */
    REVIEW,
    /** Never treat this word as bad (e.g. "scunthorpe", "dickson"). */
    ALLOW
}

enum class TermMatch {
    /** Matches a whole word only: "ass" blocks "Ass" but not "Glass" or "Cassie". */
    WORD,
    /** Matches anywhere, even inside other words. Use only for severe terms. */
    CONTAINS
}

@Entity
@Table(
    name = "blocked_term",
    uniqueConstraints = [UniqueConstraint(name = "uk_blocked_term_term", columnNames = ["term"])]
)
class BlockedTerm(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    /** Lowercase word or phrase, e.g. "cash app". */
    @Column(nullable = false, length = 100)
    val term: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    val action: TermAction = TermAction.BLOCK,

    @Enumerated(EnumType.STRING)
    @Column(name = "match_type", nullable = false, length = 10)
    val matchType: TermMatch = TermMatch.WORD,

    /** Free text for yourself, e.g. "impersonation" or "scam". */
    @Column(length = 100)
    val note: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now()
)
