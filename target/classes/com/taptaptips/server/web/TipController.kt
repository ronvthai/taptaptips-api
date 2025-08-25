package com.taptaptips.server.web

import com.taptaptips.server.domain.Tip
import com.taptaptips.server.repo.AppUserRepository
import com.taptaptips.server.repo.TipRepository
import org.springframework.http.ResponseEntity
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.*
import kotlin.math.abs

@RestController
@RequestMapping("/tips")
class TipController(
    private val tipRepo: TipRepository,
    private val userRepo: AppUserRepository,
    private val simp: SimpMessagingTemplate
) {
    data class TipReq(
        val id: UUID,
        val senderId: UUID,
        val receiverId: UUID,
        val amount: BigDecimal,
        val nonce: String,
        val timestamp: Long,
        val signatureBase64: String,
        val signingDevicePublicKeyBase64: String
    )

    @PostMapping
    fun create(@RequestBody r: TipReq): ResponseEntity<Any> {
        // Timestamp window: 2 minutes
        if (abs(System.currentTimeMillis() - r.timestamp) > 120_000) return ResponseEntity.status(400).body("stale timestamp")

        // Verify Ed25519 signature over canonical message (but we won't store signature since your table doesn't have a column)
        val msg = "${r.id}|${r.senderId}|${r.receiverId}|${r.amount}|${r.nonce}|${r.timestamp}".toByteArray()
        val pubSpec = X509EncodedKeySpec(Base64.getDecoder().decode(r.signingDevicePublicKeyBase64))
        val kf = KeyFactory.getInstance("Ed25519")
        val pub = kf.generatePublic(pubSpec)
        val s = Signature.getInstance("Ed25519")
        s.initVerify(pub)
        s.update(msg)
        if (!s.verify(Base64.getDecoder().decode(r.signatureBase64))) {
            return ResponseEntity.status(400).body("bad signature")
        }

        // Idempotency: if id exists, return DUPLICATE
        if (tipRepo.findById(r.id).isPresent) {
            return ResponseEntity.ok(mapOf("status" to "DUPLICATE", "id" to r.id))
        }

        val sender = userRepo.findById(r.senderId).orElseThrow()
        val receiver = userRepo.findById(r.receiverId).orElseThrow()

        val tip = tipRepo.save(
            Tip(
                id = r.id,
                sender = sender,
                receiver = receiver,
                amount = r.amount,
                nonce = r.nonce,
                createdAt = Instant.now(),
                timestamp = r.timestamp,
                verified = true   // verified by signature check above
            )
        )

        // Push to receiver channel
        simp.convertAndSend("/topic/user/${receiver.id}", mapOf(
            "type" to "TIP_RECEIVED", "tipId" to tip.id, "amount" to tip.amount, "senderId" to sender.id
        ))

        return ResponseEntity.ok(mapOf("status" to "CONFIRMED", "id" to tip.id))
    }
}
