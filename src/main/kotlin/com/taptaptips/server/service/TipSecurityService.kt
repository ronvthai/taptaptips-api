package com.taptaptips.server.service

import com.taptaptips.server.repo.AppUserRepository
import com.taptaptips.server.repo.DeviceRepository
import com.taptaptips.server.repo.TipRepository
import com.taptaptips.server.security.Ed25519VerifierByteKey
import com.taptaptips.server.web.CreateTipRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import kotlin.math.abs

@Service
class TipSecurityService(
    private val users: AppUserRepository,
    private val devices: DeviceRepository,
    private val tips: TipRepository,
    private val verifier: Ed25519VerifierByteKey,
    @Value("\${tips.maxAmount:500}") private val maxAmount: BigDecimal,
    @Value("\${tips.clockSkewSeconds:120}") private val skewSeconds: Long
) {
    data class VerifiedInput(
        val senderId: UUID,
        val receiverId: UUID,
        val amount: BigDecimal,
        val nonce: String,
        val timestamp: Long
    )
    object AlreadyProcessed : RuntimeException()

    fun verifyRequest(req: CreateTipRequest, authUserId: UUID): VerifiedInput {
        // 1) Sender bound to auth principal
        if (req.senderId != authUserId) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Sender mismatch")

        // 2) Receiver exists and isn't the sender
        val receiver = users.findById(req.receiverId).orElseThrow {
            ResponseStatusException(HttpStatus.BAD_REQUEST, "Receiver not found")
        }
        if (req.senderId == receiver.id) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot tip yourself")

        // 3) Amount bounds
        if (req.amount <= BigDecimal.ZERO || req.amount > maxAmount)
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid amount")

        // 4) Timestamp window (anti-replay)
        val deltaMs = abs(Instant.now().toEpochMilli() - req.timestamp)
        if (deltaMs > skewSeconds * 1000) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Clock skew too large")

        // 5) Nonce fast check (idempotency)
        if (tips.existsBySender_IdAndNonce(req.senderId, req.nonce)) throw AlreadyProcessed

        // 6) Signature against ANY registered device for sender
        val senderDevices = devices.findAllByUser_Id(req.senderId)
        if (senderDevices.isEmpty()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "No device registered")

        // Canonical message with normalized amount
        val amt = req.amount.stripTrailingZeros().toPlainString()
        val canonical = "${req.senderId}|${req.receiverId}|${amt}|${req.nonce}|${req.timestamp}"
        val msg = canonical.toByteArray(StandardCharsets.UTF_8)

        val ok = senderDevices.any { dev ->
            try { verifier.verify(dev.publicKey, msg, req.signature) } catch (_: Exception) { false }
        }
        if (!ok) throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Bad signature")

        return VerifiedInput(req.senderId, req.receiverId, req.amount, req.nonce, req.timestamp)
    }
}
