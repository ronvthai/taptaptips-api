package com.taptaptips.server.web

import com.taptaptips.server.domain.Tip
import com.taptaptips.server.repo.AppUserRepository
import com.taptaptips.server.repo.TipRepository
import com.taptaptips.server.service.TipSecurityService
import com.taptaptips.server.service.TipSecurityService.AlreadyProcessed
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/tips")
class TipController(
    private val tips: TipRepository,
    private val users: AppUserRepository,
    private val security: TipSecurityService
) {
    private fun authUserId(): UUID =
        UUID.fromString(SecurityContextHolder.getContext().authentication.name)

    @PostMapping
    @Transactional
    fun create(@RequestBody req: CreateTipRequest): TipResult {
        val auth = authUserId()
        val v = try { security.verifyRequest(req, auth) }
                catch (e: AlreadyProcessed) { return TipResult("DUPLICATE") }

        val sender = users.findById(v.senderId).orElseThrow()
        val receiver = users.findById(v.receiverId).orElseThrow()

        return try {
            tips.save(
                Tip(
                    sender = sender,
                    receiver = receiver,
                    amount = v.amount,
                    createdAt = Instant.now(),
                    nonce = v.nonce,
                    timestamp = v.timestamp,
                    verified = true
                )
            )
            TipResult("CONFIRMED")
        } catch (e: DataIntegrityViolationException) {
            // DB uniqueness race on (sender_id, nonce)
            TipResult("DUPLICATE")
        }
    }
}
