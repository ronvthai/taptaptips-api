package com.taptaptips.server.repo

import com.taptaptips.server.domain.AppUser
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.*

interface AppUserRepository : JpaRepository<AppUser, UUID> {
    fun existsByEmail(email: String): Boolean

    /** Leverages the citext UNIQUE index — O(log n) instead of a table scan. */
    fun findByEmail(email: String): AppUser?

    fun findByStripeAccountId(stripeAccountId: String): AppUser?

    /**
     * SELECT ... FOR UPDATE on the receiver row. Used only on the held-tip
     * path so two simultaneous tips can't both slip under the per-receiver
     * holding cap. Must be called inside a transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM AppUser u WHERE u.id = :id")
    fun findByIdForUpdate(@Param("id") id: UUID): AppUser?
}