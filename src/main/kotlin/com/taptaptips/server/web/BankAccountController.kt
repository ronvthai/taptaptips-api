package com.taptaptips.server.web

import com.taptaptips.server.domain.BankAccount
import com.taptaptips.server.repo.AppUserRepository
import com.taptaptips.server.repo.BankAccountRepository
import com.taptaptips.server.security.CryptoService
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.*

@RestController
@RequestMapping("/users/{id}/bank")
class BankAccountController(
    private val users: AppUserRepository,
    private val banks: BankAccountRepository,
    private val crypto: CryptoService
) {
    private fun authUserId() = UUID.fromString(SecurityContextHolder.getContext().authentication.name)

    // Optional: ABA routing checksum (basic)
    private fun isValidRouting(n: String): Boolean {
        if (n.length !in 6..12 || !n.all(Char::isDigit)) return false
        if (n.length == 9) {
            val d = n.map { it - '0' }
            val sum = 3*(d[0]+d[3]+d[6]) + 7*(d[1]+d[4]+d[7]) + (d[2]+d[5]+d[8])
            if (sum % 10 != 0) return false
        }
        return true
    }

    @GetMapping("/summary")
    fun summary(@PathVariable id: UUID): BankSummary {
        if (id != authUserId()) throw ResponseStatusException(HttpStatus.FORBIDDEN)
        val ba = banks.findFirstByUserIdAndIsDefaultTrue(id)
        return if (ba == null) BankSummary(false)
               else BankSummary(true, ba.last4, ba.accountType, ba.institutionName)
    }

    @PostMapping("/link/manual")
    @ResponseStatus(HttpStatus.CREATED)
    fun linkManual(@PathVariable id: UUID, @RequestBody r: ManualLinkRequest) {
        if (id != authUserId()) throw ResponseStatusException(HttpStatus.FORBIDDEN)
        require(isValidRouting(r.routingNumber)) { "Invalid routing number" }
        require(r.accountNumber.all(Char::isDigit) && r.accountNumber.length in 6..17) { "Invalid account number" }

        val user = users.findById(id).orElseThrow()

        // demote previous default
        banks.findFirstByUserIdAndIsDefaultTrue(id)?.let {
            it.isDefault = false
            it.updatedAt = Instant.now()
            banks.save(it)
        }

        // encrypt sensitive values
        val routingEnc = crypto.encrypt(r.routingNumber)
        val accountEnc = crypto.encrypt(r.accountNumber)

        banks.save(
            BankAccount(
                user = user,
                last4 = r.accountNumber.takeLast(4),
                accountType = r.accountType.uppercase(),
                institutionName = r.institutionName,
                routingEnc = routingEnc,
                accountEnc = accountEnc,
                isDefault = true,
                status = "VERIFIED"
            )
        )
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun unlink(@PathVariable id: UUID) {
        if (id != authUserId()) throw ResponseStatusException(HttpStatus.FORBIDDEN)
        banks.findFirstByUserIdAndIsDefaultTrue(id)?.let { banks.delete(it) }
    }
}
