package com.taptaptips.server.repo

import com.taptaptips.server.domain.AppUser
import com.taptaptips.server.domain.PasswordResetToken
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

interface PasswordResetTokenRepository : JpaRepository<PasswordResetToken, UUID> {
    
    fun findByToken(token: String): PasswordResetToken?
    
    fun findByUser(user: AppUser): List<PasswordResetToken>
    
    @Modifying
    @Query("DELETE FROM PasswordResetToken t WHERE t.expiresAt < :now OR t.used = true")
    fun deleteExpiredAndUsedTokens(now: Instant = Instant.now()): Int
    
    @Modifying
    @Query("UPDATE PasswordResetToken t SET t.used = true WHERE t.user = :user AND t.used = false")
    fun invalidateAllUserTokens(user: AppUser): Int
}