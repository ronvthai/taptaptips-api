package com.taptaptips.server.repo

import com.taptaptips.server.domain.UserSignal
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface UserSignalRepository : JpaRepository<UserSignal, UUID> {
    fun findByUserIdAndTypeAndValue(userId: UUID, type: String, value: String): UserSignal?
    fun findByUserId(userId: UUID): List<UserSignal>

    /** How many different users have used this value (e.g. one bank account). */
    @Query("SELECT COUNT(DISTINCT s.userId) FROM UserSignal s WHERE s.type = :type AND s.value = :value")
    fun countUsersWith(@Param("type") type: String, @Param("value") value: String): Long
}
