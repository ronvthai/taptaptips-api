package com.taptaptips.server.repo

import com.taptaptips.server.domain.Tip
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.time.LocalDate
import java.util.*

interface TipRepository : JpaRepository<Tip, UUID> {

    fun existsBySender_IdAndNonce(senderId: UUID, nonce: String): Boolean

    // ── DEPRECATED: Instant-based queries (kept for backward compatibility) ──
    fun findByReceiver_IdAndCreatedAtBetween(
        receiverId: UUID,
        startDate: Instant,
        endDate: Instant
    ): List<Tip>

    fun findBySender_IdAndCreatedAtBetween(
        senderId: UUID,
        startDate: Instant,
        endDate: Instant
    ): List<Tip>

    // ── FIX: FETCH JOIN eliminates N+1 on sender/receiver display names ──
    // Each of these issues exactly ONE SQL query regardless of result size.
    // Previously, mapping tip.sender?.displayName in the controller triggered
    // a separate SELECT per tip (N+1). The FETCH JOIN collapses that into a
    // single JOIN query, and the Page<Tip> overload adds LIMIT/OFFSET so a
    // receiver with hundreds of tips never loads them all into memory at once.

    @Query("""
        SELECT t FROM Tip t
        LEFT JOIN FETCH t.sender
        LEFT JOIN FETCH t.receiver
        WHERE t.receiver.id = :receiverId
          AND t.createdAtLocal BETWEEN :startDate AND :endDate
        ORDER BY t.createdAt DESC
    """)
    fun findByReceiver_IdAndCreatedAtLocalBetween(
        @Param("receiverId") receiverId: UUID,
        @Param("startDate") startDate: LocalDate,
        @Param("endDate") endDate: LocalDate,
        pageable: Pageable
    ): Page<Tip>

    @Query("""
        SELECT t FROM Tip t
        LEFT JOIN FETCH t.sender
        LEFT JOIN FETCH t.receiver
        WHERE t.sender.id = :senderId
          AND t.createdAtLocal BETWEEN :startDate AND :endDate
        ORDER BY t.createdAt DESC
    """)
    fun findBySender_IdAndCreatedAtLocalBetween(
        @Param("senderId") senderId: UUID,
        @Param("startDate") startDate: LocalDate,
        @Param("endDate") endDate: LocalDate,
        pageable: Pageable
    ): Page<Tip>

    @Query("""
        SELECT t FROM Tip t
        LEFT JOIN FETCH t.sender
        LEFT JOIN FETCH t.receiver
        WHERE t.receiver.id = :receiverId
          AND t.createdAtLocal = :date
        ORDER BY t.createdAt DESC
    """)
    fun findByReceiver_IdAndCreatedAtLocal(
        @Param("receiverId") receiverId: UUID,
        @Param("date") date: LocalDate,
        pageable: Pageable
    ): Page<Tip>

    // ── Webhook lookups: FETCH JOIN already present, keep as-is ──
    @Query("SELECT t FROM Tip t LEFT JOIN FETCH t.sender LEFT JOIN FETCH t.receiver WHERE t.paymentIntentId = :id")
    fun findByPaymentIntentId(@Param("id") id: String): Tip?

    @Query("SELECT t FROM Tip t LEFT JOIN FETCH t.sender LEFT JOIN FETCH t.receiver WHERE t.chargeId = :id")
    fun findByChargeId(@Param("id") id: String): Tip?

    fun findByNonce(nonce: String): Tip?
    fun countBySender_Id(senderId: UUID): Long

    @Query("SELECT COUNT(t) FROM Tip t WHERE t.sender.id = :senderId AND t.status = 'DISPUTED'")
    fun countDisputesBySender(@Param("senderId") senderId: UUID): Long
}