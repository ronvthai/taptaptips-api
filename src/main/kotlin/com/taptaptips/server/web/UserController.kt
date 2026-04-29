package com.taptaptips.server.web

import com.taptaptips.server.repo.AppUserRepository
import com.taptaptips.server.repo.BankAccountRepository
import com.taptaptips.server.repo.DeviceRepository
import com.taptaptips.server.repo.FcmTokenRepository
import com.taptaptips.server.repo.PasswordResetTokenRepository
import com.taptaptips.server.repo.TipRepository
import com.taptaptips.server.service.StripePaymentService
import org.slf4j.LoggerFactory
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest
import java.time.Duration
import java.util.*
import java.util.concurrent.ConcurrentHashMap

@RestController
@RequestMapping("/users")
class UserController(
    private val users: AppUserRepository,
    private val devices: DeviceRepository,
    private val fcmTokens: FcmTokenRepository,
    private val bankAccounts: BankAccountRepository,
    private val passwordResetTokens: PasswordResetTokenRepository,
    private val tips: TipRepository,
    private val stripeService: StripePaymentService
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val encoder = BCryptPasswordEncoder()

    data class UserStatusResponse(
        val suspended: Boolean,
        val suspensionReason: String?,
        val suspendedAt: String?
    )

    companion object {
        // In-memory cache: endpointId -> userId
        // TODO: Move to Redis/database for production
        private val endpointMappings = ConcurrentHashMap<String, UUID>()
    }

    /** Returns the verified caller UUID from the JWT (set by JwtAuthFilter). */
    private fun authUserId(): UUID =
        UUID.fromString(SecurityContextHolder.getContext().authentication.name)

    data class UserLookupResponse(
        val id: UUID,
        val username: String,
        val displayName: String?
    )

    // ============================================
    // Account Deletion (Apple Guideline 5.1.1)
    // ============================================

    /**
     * DELETE /users/{id}
     *
     * Permanently deletes the caller's account and all associated data.
     * Requires the caller's current password for confirmation so that a stolen
     * JWT alone cannot trigger deletion.
     *
     * Cascade order:
     *   1. Password verification (abort early on mismatch)
     *   2. FCM tokens       — stops push delivery immediately
     *   3. BLE token        — removes from in-memory map
     *   4. Devices          — clears Ed25519 public keys
     *   5. Password reset tokens
     *   6. Bank accounts
     *   7. Tips             — null out sender/receiver FK to preserve dispute
     *                         records for Stripe/tax purposes; raw amounts remain
     *   8. Stripe Customer  — detaches all saved cards
     *   9. Stripe Connect account — queued for deletion by Stripe
     *  10. app_user row     — hard delete
     *
     * Tip rows are NOT deleted: they are financial records that Stripe and the
     * platform may need for disputes and 1099-K reporting. Instead, the sender
     * and receiver FK columns are set to NULL so no PII can be retrieved through
     * the tip. If your jurisdiction requires full erasure, swap the null-out for
     * a hard delete after confirming no open disputes exist.
     */
    @DeleteMapping("/{id}")
    @Transactional
    fun deleteAccount(
        @PathVariable id: UUID,
        @RequestBody body: DeleteAccountRequest
    ): ResponseEntity<Any> {
        // IDOR: callers may only delete their own account
        val callerId = authUserId()
        if (callerId != id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "You may only delete your own account")
        }

        val user = users.findById(id).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")
        }

        // Require current password — a stolen JWT alone must not be enough
        if (!encoder.matches(body.currentPassword, user.passwordHash)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Incorrect password")
        }

        log.info("🗑️ Account deletion requested for user $id")

        // 1. FCM tokens — stop push delivery before anything else
        fcmTokens.deleteByUser_Id(id)
        log.info("   ✓ FCM tokens deleted")

        // 2. BLE token — remove from in-memory discovery map
        endpointMappings.entries.removeIf { it.value == id }
        log.info("   ✓ BLE endpoint mappings cleared")

        // 3. Devices (Ed25519 public keys)
        val userDevices = devices.findAllByUser_Id(id)
        devices.deleteAll(userDevices)
        log.info("   ✓ Devices deleted (${userDevices.size})")

        // 4. Password reset tokens
        val resetTokens = passwordResetTokens.findByUser(user)
        passwordResetTokens.deleteAll(resetTokens)
        log.info("   ✓ Password reset tokens deleted")

        // 5. Bank account records
        val banks = bankAccounts.findAllByUserId(id)
        bankAccounts.deleteAll(banks)
        log.info("   ✓ Bank account records deleted")

        // 6. Null out tip FK references — preserve financial records, strip PII
        //    Tips where this user was the sender
        val sentTips = tips.findBySender_IdAndCreatedAtBetween(
            id,
            java.time.Instant.EPOCH,
            java.time.Instant.now()
        )
        sentTips.forEach { it.sender?.let { _ -> /* sender is a val — handled via JPQL below */ } }

        // Use a bulk JPQL update to null out the FK without loading every tip entity
        tips.nullifySenderReferences(id)
        tips.nullifyReceiverReferences(id)
        log.info("   ✓ Tip FK references nullified (${sentTips.size} sent)")

        // 7. Stripe Customer — detach saved cards, then delete customer
        val stripeCustomerId = user.stripeCustomerId
        if (stripeCustomerId != null) {
            try {
                stripeService.deleteCustomer(stripeCustomerId)
                log.info("   ✓ Stripe customer $stripeCustomerId deleted")
            } catch (e: Exception) {
                // Log but don't abort — we still want the account row gone
                log.warn("   ⚠️ Stripe customer deletion failed (continuing): ${e.message}")
            }
        }

        // 8. Stripe Connect account — queue for deletion (Express accounts can
        //    only be deleted after all pending payouts settle; Stripe will reject
        //    if a balance remains. Log and continue in that case.)
        val stripeAccountId = user.stripeAccountId
        if (stripeAccountId != null) {
            try {
                stripeService.deleteConnectedAccount(stripeAccountId)
                log.info("   ✓ Stripe Connect account $stripeAccountId deleted")
            } catch (e: Exception) {
                log.warn("   ⚠️ Stripe Connect account deletion failed (continuing): ${e.message}")
            }
        }

        // 9. Delete the user row — foreign-key cascades handle any remaining child rows
        users.delete(user)
        log.info("✅ Account $id fully deleted")

        return ResponseEntity.noContent().build()
    }

    data class DeleteAccountRequest(
        val currentPassword: String
    )

    // ============================================
    // Endpoint registration (BLE discovery)
    // ============================================

    @PostMapping("/register-endpoint")
    fun registerEndpoint(
        @RequestParam endpointId: String,
        @AuthenticationPrincipal userId: UUID
    ): ResponseEntity<Map<String, String>> {
        if (!endpointId.matches(Regex("^BLE-[A-Z0-9]{8}$"))) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Invalid endpointId format. Expected: BLE-XXXXXXXX"
            )
        }

        users.findById(userId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "User not found: $userId")
        }

        endpointMappings[endpointId] = userId

        return ResponseEntity.ok(mapOf(
            "status" to "registered",
            "endpointId" to endpointId,
            "userId" to userId.toString()
        ))
    }

    @GetMapping("/lookup-by-endpoint")
    fun lookupUserByEndpoint(@RequestParam endpointId: String): ResponseEntity<UserLookupResponse> {
        val userId = endpointMappings[endpointId]
            ?: throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "No user found for endpoint: $endpointId. User may need to restart their app."
            )

        val user = users.findById(userId).orElseThrow {
            endpointMappings.remove(endpointId)
            ResponseStatusException(HttpStatus.NOT_FOUND, "User not found: $userId")
        }

        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
            .body(UserLookupResponse(
                id = user.id,
                username = user.email,
                displayName = user.displayName
            ))
    }

    @DeleteMapping("/endpoint/{endpointId}")
    fun clearEndpoint(
        @PathVariable endpointId: String,
        @AuthenticationPrincipal userId: UUID
    ): ResponseEntity<Map<String, String>> {
        val mappedUserId = endpointMappings[endpointId]

        if (mappedUserId != userId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot clear endpoint for different user")
        }

        endpointMappings.remove(endpointId)

        return ResponseEntity.ok(mapOf("status" to "cleared", "endpointId" to endpointId))
    }

    // ============================================
    // Avatar Endpoints
    // ============================================

    @PutMapping("/{id}/avatar", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadAvatar(
        @PathVariable id: UUID,
        @RequestParam("file") file: MultipartFile
    ): ResponseEntity<Any> {
        if (authUserId() != id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "You may only update your own avatar")
        }

        if (file.isEmpty) return ResponseEntity.badRequest().body("empty file")
        if (file.size > 2_000_000) return ResponseEntity.badRequest().body("file too large")

        val u = users.findById(id).orElseThrow()
        u.profilePicture = file.bytes
        u.updatedAt = java.time.Instant.now()
        users.save(u)
        return ResponseEntity.ok(mapOf("status" to "OK"))
    }

    @GetMapping("/{id}/avatar")
    fun getAvatar(@PathVariable id: UUID): ResponseEntity<ByteArray> {
        val u = users.findById(id).orElseThrow()
        val bytes = u.profilePicture ?: return ResponseEntity.notFound().build()

        val md5 = MessageDigest.getInstance("MD5").digest(bytes)
        val etag = md5.joinToString("") { "%02x".format(it) }

        return ResponseEntity.ok()
            .contentType(MediaType.IMAGE_JPEG)
            .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic())
            .eTag(etag)
            .header(HttpHeaders.CONTENT_LENGTH, bytes.size.toString())
            .body(bytes)
    }

    // ============================================
    // Public Key DTOs & Endpoints
    // ============================================

    data class PublicKeyResponse(
        val userId: String,
        val publicKey: String,
        val deviceId: String,
        val deviceName: String
    )

    data class PublicKeyBatchRequest(
        val userIds: List<String>
    )

    @GetMapping("/{userId}/publicKey")
    fun getPublicKey(@PathVariable userId: UUID): ResponseEntity<PublicKeyResponse> {
        users.findById(userId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "User not found: $userId")
        }

        val userDevices = devices.findAllByUser_Id(userId)

        if (userDevices.isEmpty()) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "No devices registered for user: $userId")
        }

        val device = userDevices
            .filter { it.isActive }
            .maxByOrNull { it.updatedAt }
            ?: userDevices.maxByOrNull { it.updatedAt }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No valid device found")

        if (device.publicKey.isEmpty()) {
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Device has no public key registered")
        }

        if (device.publicKey.size != 32) {
            throw ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Invalid public key size: ${device.publicKey.size} bytes (expected 32)"
            )
        }

        val publicKeyB64 = java.util.Base64.getEncoder().encodeToString(device.publicKey)

        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(Duration.ofMinutes(30)).cachePublic())
            .body(PublicKeyResponse(
                userId = userId.toString(),
                publicKey = publicKeyB64,
                deviceId = device.id.toString(),
                deviceName = device.deviceName ?: "Unknown"
            ))
    }

    @GetMapping("/me/status")
    fun getMyStatus(@AuthenticationPrincipal userIdStr: String): UserStatusResponse {
        val userId = UUID.fromString(userIdStr)
        val user = users.findById(userId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")
        }

        return UserStatusResponse(
            suspended = user.suspended,
            suspensionReason = user.suspensionReason,
            suspendedAt = user.suspendedAt?.toString()
        )
    }

    @PostMapping("/publicKeys")
    fun getPublicKeys(@RequestBody request: PublicKeyBatchRequest): ResponseEntity<Map<String, PublicKeyResponse>> {
        val results = mutableMapOf<String, PublicKeyResponse>()

        request.userIds.forEach { userIdStr ->
            try {
                val userId = UUID.fromString(userIdStr)
                val userDevices = devices.findAllByUser_Id(userId)
                if (userDevices.isEmpty()) return@forEach

                val device = userDevices
                    .filter { it.isActive }
                    .maxByOrNull { it.updatedAt }
                    ?: userDevices.maxByOrNull { it.updatedAt }
                    ?: return@forEach

                if (device.publicKey.isNotEmpty() && device.publicKey.size == 32) {
                    val publicKeyB64 = java.util.Base64.getEncoder().encodeToString(device.publicKey)
                    results[userIdStr] = PublicKeyResponse(
                        userId = userIdStr,
                        publicKey = publicKeyB64,
                        deviceId = device.id.toString(),
                        deviceName = device.deviceName ?: "Unknown"
                    )
                }
            } catch (e: Exception) {
                // Skip invalid user IDs
            }
        }

        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(Duration.ofMinutes(30)).cachePublic())
            .body(results)
    }

    // ============================================
    // User Profile Updates
    // ============================================

    data class UpdateUserReq(
        val email: String?,
        /** Display name — max 100 chars enforced below. */
        val displayName: String?,
        val password: String?,
        /**
         * Required when changing password.
         * A stolen JWT alone must not be sufficient to lock out the real owner.
         */
        val currentPassword: String?
    )

    @PatchMapping("/{id}")
    fun updateUser(
        @PathVariable id: UUID,
        @RequestBody body: UpdateUserReq
    ): ResponseEntity<Any> {
        if (authUserId() != id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "You may only update your own profile")
        }

        // ── Input length limits ──────────────────────────────────────────────
        if (body.email != null && body.email.length > 254) {
            return ResponseEntity.badRequest().body(mapOf("error" to "Email too long (max 254 chars)"))
        }
        if (body.displayName != null && body.displayName.length > 100) {
            return ResponseEntity.badRequest().body(mapOf("error" to "Display name too long (max 100 chars)"))
        }

        // ── Password change requires current password ────────────────────────
        val u = users.findById(id).orElseThrow()

        if (body.password != null) {
            if (body.password.length < 8) {
                return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(mapOf("error" to "Password must be at least 8 characters long"))
            }
            if (body.currentPassword.isNullOrBlank()) {
                return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(mapOf("error" to "currentPassword is required to change your password"))
            }
            if (!encoder.matches(body.currentPassword, u.passwordHash)) {
                return ResponseEntity
                    .status(HttpStatus.FORBIDDEN)
                    .body(mapOf("error" to "Incorrect current password"))
            }
        }

        var changed = false
        body.email?.let {
            if (u.email != it && users.existsByEmail(it)) {
                return ResponseEntity.status(409).body("email exists")
            }
            changed = true
        }
        body.displayName?.let { changed = true }
        body.password?.let { changed = true }

        if (!changed) return ResponseEntity.ok(mapOf("status" to "NOOP"))

        val newEntity = com.taptaptips.server.domain.AppUser(
            id           = u.id,
            email        = body.email ?: u.email,
            passwordHash = body.password?.let { encoder.encode(it) } ?: u.passwordHash,
            displayName  = body.displayName ?: u.displayName,
            createdAt    = u.createdAt,
            updatedAt    = java.time.Instant.now(),
            profilePicture = u.profilePicture,
            wantsToReceiveTips = u.wantsToReceiveTips,
            stripeAccountId    = u.stripeAccountId,
            stripeOnboarded    = u.stripeOnboarded,
            stripeLastChecked  = u.stripeLastChecked,
            bankLast4          = u.bankLast4,
            bankName           = u.bankName,
            stripeCustomerId       = u.stripeCustomerId,
            defaultPaymentMethodId = u.defaultPaymentMethodId
        )

        users.save(newEntity)
        return ResponseEntity.ok(mapOf("status" to "OK"))
    }
}
