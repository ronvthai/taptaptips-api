package com.taptaptips.server.repo

import com.taptaptips.server.domain.FcmToken
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface FcmTokenRepository : JpaRepository<FcmToken, UUID> {
    
    /**
     * Find all active tokens for a user (supports multiple devices)
     */
    fun findByUser_IdAndIsActiveTrue(userId: UUID): List<FcmToken>
    
    /**
     * Find token by exact token string
     */
    fun findByToken(token: String): FcmToken?
    
    /**
     * Delete all tokens for a user (when they log out from all devices)
     */
    fun deleteByUser_Id(userId: UUID)
    
    /**
     * Deactivate a specific token (when FCM reports it as invalid)
     */
    @Modifying
    @Query("UPDATE FcmToken f SET f.isActive = false WHERE f.token = :token")
    fun deactivateToken(token: String)
}
