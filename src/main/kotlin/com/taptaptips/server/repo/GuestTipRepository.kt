package com.taptaptips.server.repo

import com.taptaptips.server.domain.GuestTip
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

interface GuestTipRepository : JpaRepository<GuestTip, UUID> {
    fun findByPaymentIntentId(paymentIntentId: String): GuestTip?

    @Query("SELECT g.paymentIntentId FROM GuestTip g WHERE g.status = 'PENDING' AND g.createdAt < :before")
    fun findPendingCreatedBefore(@Param("before") before: Instant): List<String>

    /**
     * Atomically claims a pending guest tip. Returns 1 for exactly one caller,
     * even if the browser and the background sweep finish it at the same time —
     * so the Tip row is created and the receiver notified only once.
     */
    @Transactional
    @Modifying
    @Query("UPDATE GuestTip g SET g.status = :to, g.completedAt = :now WHERE g.paymentIntentId = :pi AND g.status = 'PENDING'")
    fun claim(@Param("pi") pi: String, @Param("to") to: String, @Param("now") now: Instant): Int

    @Transactional
    @Modifying
    @Query("UPDATE GuestTip g SET g.tipId = :tipId WHERE g.paymentIntentId = :pi")
    fun linkTip(@Param("pi") pi: String, @Param("tipId") tipId: UUID): Int
}
