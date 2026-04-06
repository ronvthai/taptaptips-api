package com.taptaptips.server.web

import com.taptaptips.server.repo.AppUserRepository
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Controller for BLE discovery-related endpoints.
 *
 * All user lookups use indexed repository queries — no more findAll() table scans.
 */
@RestController
@RequestMapping("/discovery")
class DiscoveryController(
    private val users: AppUserRepository
) {

    /**
     * Resolve a user by their exact email address (primary BLE discovery method).
     *
     * Uses the citext UNIQUE index — O(log n).
     */
    @GetMapping("/resolve/email/{email}")
    fun resolveByEmail(@PathVariable email: String): UserDiscoveryDto {
        val normalized = email.trim().lowercase()

        // Exact match via indexed column
        val user = users.findByEmail(normalized)
            ?: throw UserNotFoundException("No user found with email: $email")

        return user.toDiscoveryDto()
    }

    /**
     * Batch resolve by email addresses. Skips emails that don't exist.
     */
    @PostMapping("/resolve-batch-email")
    fun resolveByEmails(@RequestBody request: BatchResolveEmailRequest): BatchResolveResponse {
        val results = request.emails.mapNotNull { email ->
            runCatching { resolveByEmail(email) }.getOrNull()
        }
        return BatchResolveResponse(results = results)
    }

    /**
     * Resolve a short user ID (first 8 hex chars of the UUID) to a user.
     *
     * Uses a prefix LIKE query against the primary key — no table scan.
     * Kept for backward compatibility with older BLE advertisement payloads.
     */
    @GetMapping("/resolve/{shortId}")
    fun resolveShortId(@PathVariable shortId: String): UserDiscoveryDto {
        if (shortId.length != 8 || !shortId.matches(Regex("[0-9a-f]{8}"))) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid short ID format — expected 8 hex characters")
        }

        val matches = users.findByIdPrefix(shortId)

        return when (matches.size) {
            0    -> throw UserNotFoundException("No user found with short ID: $shortId")
            1    -> matches.first().toDiscoveryDto()
            else -> throw MultipleUsersFoundException("Multiple users match short ID: $shortId")
        }
    }

    /**
     * Batch resolve short IDs. Skips IDs that don't resolve.
     */
    @PostMapping("/resolve-batch")
    fun resolveShortIds(@RequestBody request: BatchResolveRequest): BatchResolveResponse {
        val results = request.shortIds.mapNotNull { shortId ->
            runCatching { resolveShortId(shortId) }.getOrNull()
        }
        return BatchResolveResponse(results = results)
    }

    /**
     * Display-name search — uses indexed LIKE query, capped at 50.
     */
    @GetMapping("/search")
    fun searchUsers(
        @RequestParam("query") query: String,
        @RequestParam("limit", defaultValue = "10") limit: Int
    ): List<UserDiscoveryDto> {
        if (query.length < 2) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Query must be at least 2 characters")
        }
        return users.searchByDisplayName(query)
            .take(limit.coerceIn(1, 50))
            .map { it.toDiscoveryDto() }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun com.taptaptips.server.domain.AppUser.toDiscoveryDto() = UserDiscoveryDto(
        userId      = id.toString(),
        username    = displayName,
        displayName = displayName,
        email       = email,
        hasAvatar   = profilePicture != null
    )
}

// ── DTOs ────────────────────────────────────────────────────────────────────

data class UserDiscoveryDto(
    val userId: String,
    val username: String,
    val displayName: String,
    val email: String,
    val hasAvatar: Boolean
)

data class BatchResolveRequest(val shortIds: List<String>)
data class BatchResolveEmailRequest(val emails: List<String>)
data class BatchResolveResponse(val results: List<UserDiscoveryDto>)

// ── exceptions ───────────────────────────────────────────────────────────────

class UserNotFoundException(message: String) : RuntimeException(message)
class MultipleUsersFoundException(message: String) : RuntimeException(message)
