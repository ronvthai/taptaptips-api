package com.taptaptips.server.web

import com.taptaptips.server.repo.AppUserRepository
import com.taptaptips.server.repo.DeviceRepository
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.context.SecurityContextHolder
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
    private val devices: DeviceRepository
) {
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

    @GetMapping("/endpoint-mappings")
    fun getEndpointMappings(): ResponseEntity<Map<String, String>> {
        // TODO: Add admin authorization check
        return ResponseEntity.ok(endpointMappings.mapValues { it.value.toString() })
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
        // IDOR fix: callers may only update their own avatar
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
        val displayName: String?,
        val password: String?
    )

    @PatchMapping("/{id}")
    fun updateUser(
        @PathVariable id: UUID,
        @RequestBody body: UpdateUserReq
    ): ResponseEntity<Any> {
        // IDOR fix: callers may only update their own profile
        if (authUserId() != id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "You may only update your own profile")
        }

        // Password policy: enforce minimum length on updates (same rule as registration)
        if (body.password != null && body.password.length < 8) {
            return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(mapOf("error" to "Password must be at least 8 characters long"))
        }

        val u = users.findById(id).orElseThrow()

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

        val encoder = org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
        val newEntity = com.taptaptips.server.domain.AppUser(
            id           = u.id,
            email        = body.email ?: u.email,
            passwordHash = body.password?.let { encoder.encode(it) } ?: u.passwordHash,
            displayName  = body.displayName ?: u.displayName,
            createdAt    = u.createdAt,
            updatedAt    = java.time.Instant.now(),
            profilePicture = u.profilePicture,
            // Preserve Stripe receiver fields
            wantsToReceiveTips = u.wantsToReceiveTips,
            stripeAccountId    = u.stripeAccountId,
            stripeOnboarded    = u.stripeOnboarded,
            stripeLastChecked  = u.stripeLastChecked,
            bankLast4          = u.bankLast4,
            bankName           = u.bankName,
            // Preserve Stripe sender fields
            stripeCustomerId       = u.stripeCustomerId,
            defaultPaymentMethodId = u.defaultPaymentMethodId
        )

        users.save(newEntity)
        return ResponseEntity.ok(mapOf("status" to "OK"))
    }
}
