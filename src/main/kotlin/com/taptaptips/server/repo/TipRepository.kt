package com.taptaptips.server.repo

import com.taptaptips.server.domain.Tip
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.time.LocalDate  // ⭐ NEW
import java.util.*

interface TipRepository : JpaRepository<Tip, UUID> {
    // Use nested path for the relation:
    fun existsBySender_IdAndNonce(senderId: UUID, nonce: String): Boolean
    
    // ⭐ DEPRECATED: Old methods using Instant (keep for backward compatibility)
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
    
    // ⭐ NEW: Fast queries using pre-computed local date (uses index!)
    fun findByReceiver_IdAndCreatedAtLocalBetween(
        receiverId: UUID,
        startDate: LocalDate,
        endDate: LocalDate
    ): List<Tip>
    
    fun findBySender_IdAndCreatedAtLocalBetween(
        senderId: UUID,
        startDate: LocalDate,
        endDate: LocalDate
    ): List<Tip>
    
    // ⭐ NEW: Query for single day (even faster)
    fun findByReceiver_IdAndCreatedAtLocal(
        receiverId: UUID,
        date: LocalDate
    ): List<Tip>
}