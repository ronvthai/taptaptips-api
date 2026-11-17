package com.taptaptips.server.repo

import com.taptaptips.server.domain.BannedIdentifier
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface BannedIdentifierRepository : JpaRepository<BannedIdentifier, UUID> {
    fun findByTypeAndValue(type: String, value: String): BannedIdentifier?
    fun findByTypeAndValueIn(type: String, values: Collection<String>): List<BannedIdentifier>
    fun existsByTypeAndValue(type: String, value: String): Boolean
    fun findAllByOrderByCreatedAtDesc(): List<BannedIdentifier>
}
