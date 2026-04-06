package com.taptaptips.server.web

import com.taptaptips.server.repo.AppUserRepository
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Issues and resolves opaque BLE tokens.
 *
 * Privacy design
 * ──────────────
 * BLE advertisements must never carry PII (email, UUID, display name).
 * Instead, each logged-in user is assigned a short random hex token that
 * rotates every 30 minutes.  Nearby scanners resolve the token to a display
 * name + avatar via POST /ble/resolve — the raw user identity is never
 * transmitted over the air.
 *
 * Token format: 8 lowercase hex characters (32 bits of entropy).
 * Collision probability across 100 k simultaneous tokens ≈ 0.1% — acceptable
 * for a proximity tipping app where tokens are short-lived.
 */
@RestController
@RequestMapping("/ble")
class BleTokenController(
    private val users: AppUserRepository
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val rng = SecureRandom()

    /**
     * token  → userId mapping (in-memory, lost on restart — tokens are short-lived anyway)
     * userId → token mapping (so a user always gets the same token until rotation)
     */
    private val tokenToUser = ConcurrentHashMap<String, TokenEntry>()
    private val userToToken = ConcurrentHashMap<String, String>()

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ble-token-gc").apply { isDaemon = true }
    }

    init {
        // Purge expired entries every 5 minutes
        scheduler.scheduleAtFixedRate(::purgeExpired, 5, 5, TimeUnit.MINUTES)
    }

    data class TokenEntry(
        val userId: String,
        val expiresAt: Long          // System.currentTimeMillis()
    )

    // ── issue ────────────────────────────────────────────────────────────────

    /**
     * POST /ble/token
     *
     * Returns the caller's current BLE token, issuing a new one if none exists
     * or the existing one has expired.  The token is stored in Android prefs
     * (KEY_BLE_TOKEN) and placed in the BLE advertisement payload.
     */
    @PostMapping("/token")
    fun issueToken(): BleTokenResponse {
        val userId = authUserId().toString()

        // Reuse if still valid
        val existing = userToToken[userId]
        if (existing != null) {
            val entry = tokenToUser[existing]
            if (entry != null && entry.expiresAt > System.currentTimeMillis()) {
                return BleTokenResponse(token = existing)
            }
        }

        // Generate fresh token, retry on (very unlikely) collision
        var token: String
        var attempts = 0
        do {
            token = generateToken()
            attempts++
            if (attempts > 10) throw ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR, "Token generation failed"
            )
        } while (tokenToUser.containsKey(token))

        val ttlMs = TimeUnit.MINUTES.toMillis(30)
        tokenToUser[token] = TokenEntry(userId, System.currentTimeMillis() + ttlMs)

        // Remove old token for this user if any
        val old = userToToken.put(userId, token)
        if (old != null && old != token) tokenToUser.remove(old)

        logger.debug("🔑 Issued BLE token for user ${userId.take(8)}…")
        return BleTokenResponse(token = token)
    }

    // ── resolve ──────────────────────────────────────────────────────────────

    /**
     * POST /ble/resolve
     *
     * Resolves one or more opaque BLE tokens to display-able user profiles.
     * Called by the scanner after it observes a token in a BLE advertisement.
     * No PII is in the token itself — resolution happens entirely server-side.
     *
     * Tokens that are unknown or expired are silently omitted from the response.
     */
    @PostMapping("/resolve")
    fun resolveTokens(@RequestBody request: BleResolveRequest): BleResolveResponse {
        if (request.tokens.isEmpty()) return BleResolveResponse(emptyList())
        if (request.tokens.size > 50) throw ResponseStatusException(
            HttpStatus.BAD_REQUEST, "Maximum 50 tokens per request"
        )

        val now = System.currentTimeMillis()
        val results = request.tokens.mapNotNull { token ->
            val entry = tokenToUser[token] ?: return@mapNotNull null
            if (entry.expiresAt <= now) return@mapNotNull null        // expired

            val user = try {
                users.findById(UUID.fromString(entry.userId)).orElse(null)
            } catch (e: Exception) { null } ?: return@mapNotNull null

            BleTokenProfile(
                token       = token,
                userId      = user.id.toString(),
                displayName = user.displayName,
                hasAvatar   = user.profilePicture != null
            )
        }

        return BleResolveResponse(results)
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun authUserId() = UUID.fromString(
        SecurityContextHolder.getContext().authentication.name
    )

    private fun generateToken(): String {
        val bytes = ByteArray(4)
        rng.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }   // 8 hex chars
    }

    private fun purgeExpired() {
        val now = System.currentTimeMillis()
        val expired = tokenToUser.entries.filter { it.value.expiresAt <= now }
        expired.forEach { (token, entry) ->
            tokenToUser.remove(token)
            userToToken.remove(entry.userId, token)
        }
        if (expired.isNotEmpty()) logger.debug("🧹 Purged ${expired.size} expired BLE tokens")
    }
}

// ── DTOs ─────────────────────────────────────────────────────────────────────

data class BleTokenResponse(val token: String)

data class BleResolveRequest(val tokens: List<String>)

data class BleTokenProfile(
    val token: String,
    val userId: String,
    val displayName: String,
    val hasAvatar: Boolean
)

data class BleResolveResponse(val results: List<BleTokenProfile>)
