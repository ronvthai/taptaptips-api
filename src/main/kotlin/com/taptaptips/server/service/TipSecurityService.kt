package com.taptaptips.server.service

import com.taptaptips.server.repo.AppUserRepository
import com.taptaptips.server.repo.DeviceRepository
import com.taptaptips.server.repo.TipRepository
import com.taptaptips.server.security.Ed25519VerifierByteKey
import com.taptaptips.server.web.CreateTipRequest
import org.slf4j.LoggerFactory
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
    private val log = LoggerFactory.getLogger(TipSecurityService::class.java)
    
    data class VerifiedInput(
        val senderId: UUID,
        val receiverId: UUID,
        val amount: BigDecimal,
        val nonce: String,
        val timestamp: Long
    )
    
    object AlreadyProcessed : RuntimeException()

    fun verifyRequest(req: CreateTipRequest, authUserId: UUID): VerifiedInput {
        log.debug("=== Verifying tip request ===")
        log.debug("Sender: ${req.senderId}")
        log.debug("Receiver: ${req.receiverId}")
        log.debug("Amount: ${req.amount}")
        log.debug("Auth User: $authUserId")
        
        // 1) Sender bound to auth principal
        if (req.senderId != authUserId) {
            log.warn("❌ Sender mismatch: req.senderId=${req.senderId}, authUserId=$authUserId")
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Sender mismatch")
        }
        log.debug("✓ Sender matches auth principal")

        // ========== 🆕 NEW: Check if sender is suspended ==========
        // 1.5) Fetch sender and check suspension status
        val sender = users.findById(req.senderId).orElseThrow {
            log.warn("❌ Sender not found: ${req.senderId}")
            ResponseStatusException(HttpStatus.BAD_REQUEST, "Sender not found")
        }
        
        if (sender.suspended) {
            log.warn("🚫 SUSPENDED USER BLOCKED: ${req.senderId}")
            log.warn("   Suspension reason: ${sender.suspensionReason}")
            log.warn("   Suspended at: ${sender.suspendedAt}")
            throw ResponseStatusException(
                HttpStatus.FORBIDDEN, 
                "Account suspended: ${sender.suspensionReason ?: "Multiple disputes detected"}"
            )
        }
        log.debug("✓ Sender is not suspended")
        // ========== END NEW CHECK ==========

        // 2) Receiver exists and isn't the sender
        val receiver = users.findById(req.receiverId).orElseThrow {
            log.warn("❌ Receiver not found: ${req.receiverId}")
            ResponseStatusException(HttpStatus.BAD_REQUEST, "Receiver not found")
        }
        if (req.senderId == receiver.id) {
            log.warn("❌ Cannot tip yourself: ${req.senderId}")
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot tip yourself")
        }
        log.debug("✓ Receiver exists and is different from sender")

        // 3) Amount bounds
        if (req.amount <= BigDecimal.ZERO || req.amount > maxAmount) {
            log.warn("❌ Invalid amount: ${req.amount} (max: $maxAmount)")
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid amount")
        }
        log.debug("✓ Amount is valid: ${req.amount}")

        // 4) Timestamp window (anti-replay)
        val nowMs = Instant.now().toEpochMilli()
        val deltaMs = abs(nowMs - req.timestamp)
        if (deltaMs > skewSeconds * 1000) {
            log.warn("❌ Clock skew too large: deltaMs=$deltaMs, allowed=${skewSeconds * 1000}ms")
            log.warn("   Server time: $nowMs, Request timestamp: ${req.timestamp}")
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Clock skew too large")
        }
        log.debug("✓ Timestamp is within acceptable range (delta: ${deltaMs}ms)")

        // 5) Nonce fast check (idempotency)
        if (tips.existsBySender_IdAndNonce(req.senderId, req.nonce)) {
            log.info("⚠️ Duplicate nonce detected: ${req.nonce} from sender: ${req.senderId}")
            throw AlreadyProcessed
        }
        log.debug("✓ Nonce is unique")

        // 6) Signature against ANY registered device for sender
        val senderDevices = devices.findAllByUser_Id(req.senderId)
        if (senderDevices.isEmpty()) {
            log.error("❌ No devices registered for sender: ${req.senderId}")
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "No device registered")
        }
        log.debug("Found ${senderDevices.size} device(s) for sender: ${req.senderId}")

        // Canonical message with normalized amount
        val amt = req.amount.stripTrailingZeros().toPlainString()
        val canonical = "${req.senderId}|${req.receiverId}|$amt|${req.nonce}|${req.timestamp}"
        val msg = canonical.toByteArray(StandardCharsets.UTF_8)

        log.debug("Canonical message: $canonical")
        log.debug("Signature (first 30 chars): ${req.signature.take(30)}...")

        // Try verifying with each device
        var verifiedDeviceId: UUID? = null
        var verifiedDeviceName: String? = null
        
        val ok = senderDevices.any { dev ->
            try {
                val result = verifier.verify(dev.publicKey, msg, req.signature)
                if (result) {
                    verifiedDeviceId = dev.id
                    verifiedDeviceName = dev.deviceName
                    log.info("✅ Signature verified with device: ${dev.id} (${dev.deviceName})")
                    true
                } else {
                    log.debug("❌ Signature failed with device: ${dev.id} (${dev.deviceName})")
                    false
                }
            } catch (e: Exception) {
                log.warn("⚠️ Exception verifying with device ${dev.id}: ${e.javaClass.simpleName} - ${e.message}")
                false
            }
        }
        
        if (!ok) {
            log.error("❌ BAD SIGNATURE - Verification failed with ALL ${senderDevices.size} device(s)")
            log.error("   Sender: ${req.senderId}")
            log.error("   Canonical: $canonical")
            log.error("   Signature: ${req.signature.take(60)}...")
            log.error("   Devices tried:")
            senderDevices.forEachIndexed { idx, dev ->
                log.error("     [$idx] deviceId=${dev.id}, name=${dev.deviceName}, keyLength=${dev.publicKey.size}")
            }
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Bad signature")
        }

        log.info("✅ TIP VERIFIED - sender=${req.senderId}, receiver=${req.receiverId}, amount=${req.amount}, device=$verifiedDeviceId ($verifiedDeviceName)")
        
        return VerifiedInput(req.senderId, req.receiverId, req.amount, req.nonce, req.timestamp)
    }
}