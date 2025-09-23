package com.taptaptips.server.repo

import com.taptaptips.server.domain.BankAccount
import org.springframework.data.jpa.repository.JpaRepository
import java.util.*

interface BankAccountRepository : JpaRepository<BankAccount, UUID> {
    fun findFirstByUserIdAndIsDefaultTrue(userId: UUID): BankAccount?
    fun findAllByUserId(userId: UUID): List<BankAccount>
}
