package com.taptaptips.server.repo

import com.taptaptips.server.domain.FcmToken
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.time.Instant
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

    /**
     * Atomic upsert — INSERT ... ON CONFLICT (token) DO UPDATE.
     *
     * Replaces a find-then-save pattern that raced under concurrent
     * requests for the same token (e.g. Firebase's own token-refresh
     * delegate firing around the same moment as an explicit post-login
     * refresh, or a client retry racing its own still-in-flight original
     * request). Two requests could both see "no existing row" via
     * findByToken() before either committed its INSERT, and the second
     * INSERT would then violate the unique constraint on `token`
     * (idx_fcm_token) — the exact DataIntegrityViolationException seen in
     * production logs.
     *
     * Doing the whole read-modify-write as one statement means Postgres
     * resolves the conflict atomically — there is no window between
     * "check" and "write" for a second request to land in.
     */
    @Modifying
    @Query(
        value = """
            INSERT INTO fcm_token (id, user_id, token, platform, device_info, created_at, updated_at, is_active)
            VALUES (:id, :userId, :token, :platform, :deviceInfo, :now, :now, true)
            ON CONFLICT (token) DO UPDATE SET
                user_id     = EXCLUDED.user_id,
                platform    = EXCLUDED.platform,
                device_info = EXCLUDED.device_info,
                updated_at  = EXCLUDED.updated_at,
                is_active   = true
        """,
        nativeQuery = true
    )
    fun upsertToken(
        id: UUID,
        userId: UUID,
        token: String,
        platform: String,
        deviceInfo: String?,
        now: Instant
    )
}
