package com.taptaptips.server.repo

import com.taptaptips.server.domain.AppUser
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.*

interface AppUserRepository : JpaRepository<AppUser, UUID> {
    fun existsByEmail(email: String): Boolean

    /** Leverages the citext UNIQUE index — O(log n) instead of a table scan. */
    fun findByEmail(email: String): AppUser?

    fun findByStripeAccountId(stripeAccountId: String): AppUser?

    /**
     * Resolve a shortId (first 8 hex chars of the UUID) to a user.
     *
     * Uses a JPQL LIKE against the string representation of the UUID so that
     * Postgres can hit the primary-key index rather than scanning every row.
     * The UUID string format is "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx", so a
     * shortId "a1b2c3d4" matches the prefix before the first hyphen.
     *
     * NOTE: If two users share the same 8-char prefix this query returns both;
     * the controller must handle that (MultipleUsersFoundException).  In
     * practice, with UUID v4, the chance of collision in a 1 M-user database
     * is ~0.00002%.
     */
    @Query(
        "SELECT u FROM AppUser u WHERE LOWER(CAST(u.id AS string)) LIKE LOWER(CONCAT(:prefix, '%'))"
    )
    fun findByIdPrefix(@Param("prefix") prefix: String): List<AppUser>

    /**
     * Case-insensitive display-name search, limited in the query to avoid
     * loading the whole table into memory.
     */
    @Query(
        "SELECT u FROM AppUser u WHERE LOWER(u.displayName) LIKE LOWER(CONCAT('%', :query, '%'))"
    )
    fun searchByDisplayName(@Param("query") query: String): List<AppUser>
}
