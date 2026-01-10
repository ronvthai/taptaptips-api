package com.taptaptips.server.web

import com.taptaptips.server.repo.AppUserRepository
import com.taptaptips.server.repo.DeviceRepository
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
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
        val suspendedAt: String?  // ISO datetime string
    )
    // ============================================
    // BLE Endpoint Mapping (In-Memory Cache)
    // ============================================
    
    companion object {
        // In-memory cache: endpointId -> userId
        // TODO: Move to Redis/database for production
        private val endpointMappings = ConcurrentHashMap<String, UUID>()
    }

    /**
     * Response for endpoint lookup
     */
    data class UserLookupResponse(
        val id: UUID,
        val username: String,
        val displayName: String?
    )

    /**
     * Register BLE endpointId mapping
     * Called when app starts to map endpointId -> userId
     */
    @PostMapping("/register-endpoint")
    fun registerEndpoint(
        @RequestParam endpointId: String,
        @AuthenticationPrincipal userId: UUID
    ): ResponseEntity<Map<String, String>> {
        // Validate endpointId format
        if (!endpointId.matches(Regex("^BLE-[A-Z0-9]{8}$"))) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Invalid endpointId format. Expected: BLE-XXXXXXXX"
            )
        }

        // Verify user exists
        users.findById(userId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "User not found: $userId")
        }

        // Store mapping
        endpointMappings[endpointId] = userId
        
        return ResponseEntity.ok(mapOf(
            "status" to "registered",
            "endpointId" to endpointId,
            "userId" to userId.toString()
        ))
    }

    /**
     * Lookup user by BLE endpointId
     * Used by clients to get full user details after BLE discovery
     */
    @GetMapping("/lookup-by-endpoint")
    fun lookupUserByEndpoint(@RequestParam endpointId: String): ResponseEntity<UserLookupResponse> {
        // Get userId from mapping
        val userId = endpointMappings[endpointId]
            ?: throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "No user found for endpoint: $endpointId. User may need to restart their app."
            )

        // Fetch user details
        val user = users.findById(userId).orElseThrow {
            // Mapping exists but user was deleted - clean up
            endpointMappings.remove(endpointId)
            ResponseStatusException(HttpStatus.NOT_FOUND, "User not found: $userId")
        }

        val response = UserLookupResponse(
            id = user.id,
            username = user.email, // Using email as username for now
            displayName = user.displayName
        )

        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
            .body(response)
    }

    /**
     * Get all active endpoint mappings (admin/debug only)
     */
    @GetMapping("/endpoint-mappings")
    fun getEndpointMappings(): ResponseEntity<Map<String, String>> {
        // TODO: Add admin authorization check
        val mappings = endpointMappings.mapValues { it.value.toString() }
        return ResponseEntity.ok(mappings)
    }

    /**
     * Clear endpoint mapping (for logout/debugging)
     */
    @DeleteMapping("/endpoint/{endpointId}")
    fun clearEndpoint(
        @PathVariable endpointId: String,
        @AuthenticationPrincipal userId: UUID
    ): ResponseEntity<Map<String, String>> {
        val mappedUserId = endpointMappings[endpointId]
        
        // Only allow users to clear their own endpoints
        if (mappedUserId != userId) {
            throw ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Cannot clear endpoint for different user"
            )
        }

        endpointMappings.remove(endpointId)
        
        return ResponseEntity.ok(mapOf(
            "status" to "cleared",
            "endpointId" to endpointId
        ))
    }

    // ============================================
    // Avatar Endpoints
    // ============================================

    @PutMapping("/{id}/avatar", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadAvatar(
        @PathVariable id: UUID,
        @RequestParam("file") file: MultipartFile
    ): ResponseEntity<Any> {
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

        val contentType = MediaType.IMAGE_JPEG
        val md5 = MessageDigest.getInstance("MD5").digest(bytes)
        val etag = md5.joinToString("") { "%02x".format(it) }

        return ResponseEntity.ok()
            .contentType(contentType)
            .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic())
            .eTag(etag)
            .header(HttpHeaders.CONTENT_LENGTH, bytes.size.toString())
            .body(bytes)
    }

    // ============================================
    // Public Key DTOs for HELLO Verification
    // ============================================
    
    /**
     * Response containing user's Ed25519 public key
     */
    data class PublicKeyResponse(
        val userId: String,              // User UUID
        val publicKey: String,           // Base64-encoded Ed25519 public key (32 bytes)
        val deviceId: String,            // Device UUID that provided the key
        val deviceName: String           // Human-readable device name
    )

    /**
     * Request for batch public key lookup
     */
    data class PublicKeyBatchRequest(
        val userIds: List<String>        // List of user UUIDs to fetch keys for
    )

    // ============================================
    // Public Key Endpoints for HELLO Verification
    // ============================================
    
    /**
     * Get Ed25519 public key for a user
     * Used by clients to verify HELLO message signatures
     * 
     * Returns the public key from the user's active (or most recent) device
     */
    @GetMapping("/{userId}/publicKey")
    fun getPublicKey(@PathVariable userId: UUID): ResponseEntity<PublicKeyResponse> {
        // Find user
        val user = users.findById(userId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "User not found: $userId")
        }
        
        // Get user's devices (prefer active devices)
        val userDevices = devices.findAllByUser_Id(userId)
        
        if (userDevices.isEmpty()) {
            throw ResponseStatusException(
                HttpStatus.NOT_FOUND, 
                "No devices registered for user: $userId"
            )
        }
        
        // Prefer active devices, fall back to most recently updated
        val device = userDevices
            .filter { it.isActive }
            .maxByOrNull { it.updatedAt }
            ?: userDevices.maxByOrNull { it.updatedAt }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No valid device found")
        
        // Validate public key
        if (device.publicKey.isEmpty()) {
            throw ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR, 
                "Device has no public key registered"
            )
        }
        
        if (device.publicKey.size != 32) {
            throw ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR, 
                "Invalid public key size: ${device.publicKey.size} bytes (expected 32)"
            )
        }
        
        // Convert to Base64
        val publicKeyB64 = java.util.Base64.getEncoder().encodeToString(device.publicKey)
        
        val response = PublicKeyResponse(
            userId = userId.toString(),
            publicKey = publicKeyB64,
            deviceId = device.id.toString(),
            deviceName = device.deviceName ?: "Unknown"
        )
        
        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(Duration.ofMinutes(30)).cachePublic())
            .body(response)
    }
    /**
     * Get current user's suspension status
     * Used by Android app to check if user is suspended before allowing tip sending
     */
    @GetMapping("/me/status")
    fun getMyStatus(@AuthenticationPrincipal userId: UUID): UserStatusResponse {
        val user = users.findById(userId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")
        }
        
        return UserStatusResponse(
            suspended = user.suspended,
            suspensionReason = user.suspensionReason,
            suspendedAt = user.suspendedAt?.toString()
        )
    }

    /**
     * Batch endpoint: Get public keys for multiple users at once
     * More efficient when verifying multiple connections
     */
    @PostMapping("/publicKeys")
    fun getPublicKeys(@RequestBody request: PublicKeyBatchRequest): ResponseEntity<Map<String, PublicKeyResponse>> {
        val results = mutableMapOf<String, PublicKeyResponse>()
        
        request.userIds.forEach { userIdStr ->
            try {
                val userId = UUID.fromString(userIdStr)
                
                // Get user's devices
                val userDevices = devices.findAllByUser_Id(userId)
                if (userDevices.isEmpty()) {
                    return@forEach  // Skip this user
                }
                
                // Get active device or most recent
                val device = userDevices
                    .filter { it.isActive }
                    .maxByOrNull { it.updatedAt }
                    ?: userDevices.maxByOrNull { it.updatedAt }
                    ?: return@forEach
                
                // Validate and encode
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
        val u = users.findById(id).orElseThrow()

        var changed = false
        body.email?.let {
            // (optional) reject if email already taken
            if (u.email != it && users.existsByEmail(it)) {
                return ResponseEntity.status(409).body("email exists")
            }
            // JPA entity email is val in your model; make it var if you want to allow change
            // or rebuild a new entity. Let's rebuild:
            u.apply { /* handled below by copy-style rebuild */ }
            changed = true
        }
        body.displayName?.let { changed = true }
        body.password?.let { changed = true }

        if (!changed) return ResponseEntity.ok(mapOf("status" to "NOOP"))

        // Rebuild entity with new fields (since email/displayName/passwordHash are vals)
        val encoder = org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
        val newEntity = com.taptaptips.server.domain.AppUser(
            id          = u.id,
            email       = body.email ?: u.email,
            passwordHash= body.password?.let { encoder.encode(it) } ?: u.passwordHash,
            displayName = body.displayName ?: u.displayName,
            createdAt   = u.createdAt,
            updatedAt   = java.time.Instant.now(),
            profilePicture = u.profilePicture,
            // Preserve Stripe receiver fields
            wantsToReceiveTips = u.wantsToReceiveTips,
            stripeAccountId = u.stripeAccountId,
            stripeOnboarded = u.stripeOnboarded,
            stripeLastChecked = u.stripeLastChecked,
            bankLast4 = u.bankLast4,
            bankName = u.bankName,
            // Preserve Stripe sender fields  
            stripeCustomerId = u.stripeCustomerId,
            defaultPaymentMethodId = u.defaultPaymentMethodId
        )

        users.save(newEntity)
        return ResponseEntity.ok(mapOf("status" to "OK"))
    }
}