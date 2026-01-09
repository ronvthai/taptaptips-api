package com.taptaptips.server.repo

import com.taptaptips.server.domain.AppUser
import org.springframework.data.jpa.repository.JpaRepository
import java.util.*

interface AppUserRepository : JpaRepository<AppUser, UUID> {
    fun existsByEmail(email: String): Boolean
    fun findByEmail(email: String): AppUser?
    // Add to your existing AppUserRepository interface
    fun findByStripeAccountId(stripeAccountId: String): AppUser?
}
