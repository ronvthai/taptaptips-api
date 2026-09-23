package com.taptaptips.server.repo

import com.taptaptips.server.domain.HeldTip
import com.taptaptips.server.domain.HeldTipStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface HeldTipRepository : JpaRepository<HeldTip, UUID> {

    @Query("SELECT h FROM HeldTip h JOIN FETCH h.tip WHERE h.tip.id = :tipId")
    fun findByTipId(@Param("tipId") tipId: UUID): HeldTip?

    @Query("SELECT h FROM HeldTip h JOIN FETCH h.tip WHERE h.id = :id")
    fun findWithTip(@Param("id") id: UUID): HeldTip?

    fun findAllByReceiverIdAndStatus(receiverId: UUID, status: HeldTipStatus): List<HeldTip>

    fun findAllByReceiverIdAndStatusIn(receiverId: UUID, statuses: Collection<HeldTipStatus>): List<HeldTip>

    // ── Limits ───────────────────────────────────────────────────────────────

    /** Total cents currently parked for a receiver (counts toward the per-receiver cap). */
    @Query("""
        SELECT COALESCE(SUM(h.grossCents), 0) FROM HeldTip h
        WHERE h.receiverId = :receiverId AND h.status IN :statuses
    """)
    fun sumGrossCentsForReceiver(
        @Param("receiverId") receiverId: UUID,
        @Param("statuses") statuses: Collection<HeldTipStatus>
    ): Long

    @Query("SELECT COUNT(h) FROM HeldTip h WHERE h.receiverId = :receiverId AND h.status IN :statuses")
    fun countForReceiver(
        @Param("receiverId") receiverId: UUID,
        @Param("statuses") statuses: Collection<HeldTipStatus>
    ): Long

    /** How much a sender has pushed into holding tips since [since] (anti-abuse cap). */
    @Query("""
        SELECT COALESCE(SUM(h.grossCents), 0) FROM HeldTip h
        WHERE h.senderId = :senderId AND h.createdAt >= :since AND h.status <> :excluded
    """)
    fun sumGrossCentsForSenderSince(
        @Param("senderId") senderId: UUID,
        @Param("since") since: Instant,
        @Param("excluded") excluded: HeldTipStatus
    ): Long

    // ── Receiver summary (for the app banner) ────────────────────────────────

    @Query("""
        SELECT COALESCE(SUM(h.netCents), 0) FROM HeldTip h
        WHERE h.receiverId = :receiverId AND h.status IN :statuses
    """)
    fun sumNetCentsForReceiver(
        @Param("receiverId") receiverId: UUID,
        @Param("statuses") statuses: Collection<HeldTipStatus>
    ): Long

    @Query("""
        SELECT MIN(h.expiresAt) FROM HeldTip h
        WHERE h.receiverId = :receiverId AND h.status = :status
    """)
    fun earliestExpiryForReceiver(
        @Param("receiverId") receiverId: UUID,
        @Param("status") status: HeldTipStatus
    ): Instant?

    // ── Scheduler sweeps ─────────────────────────────────────────────────────

    /** Receivers who have HELD money AND are flagged onboarded — candidates for release. */
    @Query("""
        SELECT DISTINCT h.receiverId FROM HeldTip h, AppUser u
        WHERE u.id = h.receiverId AND u.stripeOnboarded = true AND h.status = :status
    """)
    fun findOnboardedReceiversWithStatus(
        @Param("status") status: HeldTipStatus
    ): List<UUID>

    @Query("SELECT h.id FROM HeldTip h WHERE h.status = :status AND h.expiresAt < :now")
    fun findExpiredIds(
        @Param("now") now: Instant,
        @Param("status") status: HeldTipStatus
    ): List<UUID>

    @Query("SELECT h.id FROM HeldTip h WHERE h.status = :status AND h.updatedAt < :before")
    fun findStuckIds(
        @Param("status") status: HeldTipStatus,
        @Param("before") before: Instant
    ): List<UUID>

    // ── Atomic state claims ──────────────────────────────────────────────────
    // A bulk UPDATE with the expected current status in the WHERE clause is the
    // lock: exactly one caller gets "1 row updated", everyone else gets 0.
    // Bulk updates skip @Version, so version is bumped by hand to invalidate
    // any stale entity another thread might still be holding.

    @Modifying(clearAutomatically = true)
    @Query("""
        UPDATE HeldTip h
           SET h.status = :to, h.updatedAt = :now, h.version = h.version + 1
         WHERE h.id = :id AND h.status = :from
    """)
    fun transition(
        @Param("id") id: UUID,
        @Param("from") from: HeldTipStatus,
        @Param("to") to: HeldTipStatus,
        @Param("now") now: Instant
    ): Int

    // ── Account deletion ─────────────────────────────────────────────────────

    @Modifying
    @Query("UPDATE HeldTip h SET h.senderId = NULL WHERE h.senderId = :userId")
    fun nullifySender(@Param("userId") userId: UUID)
}
