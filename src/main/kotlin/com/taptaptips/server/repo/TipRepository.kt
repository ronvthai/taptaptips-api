package com.taptaptips.server.repo

import com.taptaptips.server.domain.Tip
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.*

interface TipRepository : JpaRepository<Tip, UUID> {
    // Use nested path for the relation:
    fun existsBySender_IdAndNonce(senderId: UUID, nonce: String): Boolean
    
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
}