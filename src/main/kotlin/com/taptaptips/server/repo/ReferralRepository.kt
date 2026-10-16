package com.taptaptips.server.repo

import com.taptaptips.server.domain.Referral
import com.taptaptips.server.domain.ReferralCode
import com.taptaptips.server.domain.ReferralStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface ReferralCodeRepository : JpaRepository<ReferralCode, UUID> {
    fun findByCode(code: String): ReferralCode?
    fun existsByCode(code: String): Boolean
}

interface ReferralRepository : JpaRepository<Referral, UUID> {
    fun findByReferredUserId(referredUserId: UUID): Referral?

    fun findAllByReferrerIdOrderByCreatedAtDesc(referrerId: UUID): List<Referral>

    fun countByReferrerIdAndStatus(referrerId: UUID, status: ReferralStatus): Long

    /** Rewards earned (queued or paid) in the window — used for the monthly cap. */
    @Query("""
        SELECT COUNT(r) FROM Referral r
        WHERE r.referrerId = :referrerId
          AND r.status IN :statuses
          AND r.qualifiedAt >= :since
    """)
    fun countRewardsSince(
        @Param("referrerId") referrerId: UUID,
        @Param("statuses") statuses: Collection<ReferralStatus>,
        @Param("since") since: Instant
    ): Long

    @Query("SELECT COALESCE(SUM(r.rewardCents), 0) FROM Referral r WHERE r.referrerId = :referrerId AND r.status = :status")
    fun sumRewardCents(@Param("referrerId") referrerId: UUID, @Param("status") status: ReferralStatus): Long

    /** Has this physical card already earned someone a referral reward? */
    @Query("""
        SELECT COUNT(r) > 0 FROM Referral r
        WHERE r.cardFingerprint = :fp
          AND r.status IN :statuses
          AND r.id <> :excludeId
    """)
    fun cardAlreadyRewarded(
        @Param("fp") fingerprint: String,
        @Param("statuses") statuses: Collection<ReferralStatus>,
        @Param("excludeId") excludeId: UUID
    ): Boolean

    @Query("SELECT r.id FROM Referral r WHERE r.status = :status AND r.qualifiedAt <= :before")
    fun findPayableIds(@Param("status") status: ReferralStatus, @Param("before") before: Instant): List<UUID>

    @Query("SELECT r.id FROM Referral r WHERE r.status = :status AND r.updatedAt <= :before")
    fun findStuckIds(@Param("status") status: ReferralStatus, @Param("before") before: Instant): List<UUID>

    /** Atomic claim — only one worker can move a row out of [from]. */
    @Modifying
    @Query("UPDATE Referral r SET r.status = :to, r.updatedAt = :now WHERE r.id = :id AND r.status = :from")
    fun transition(
        @Param("id") id: UUID,
        @Param("from") from: ReferralStatus,
        @Param("to") to: ReferralStatus,
        @Param("now") now: Instant
    ): Int
}
