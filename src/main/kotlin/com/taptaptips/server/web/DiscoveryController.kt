package com.taptaptips.server.web

import com.taptaptips.server.repo.AppUserRepository
import org.springframework.web.bind.annotation.*
import java.util.UUID

/**
 * Controller for BLE discovery-related endpoints
 * 
 * Handles both UUID-based and email-based discovery
 */
@RestController
@RequestMapping("/discovery")
class DiscoveryController(
    private val users: AppUserRepository
) {
    
    /**
     * Resolve user by email address (PRIMARY METHOD for BLE discovery)
     * 
     * BLE advertisements now broadcast the user's email address directly.
     * This endpoint resolves the email to full user profile info.
     * 
     * Example:
     *   Email: "player2@gmail.com"
     *   Returns: {userId, displayName, hasAvatar, etc.}
     */
    @GetMapping("/resolve/email/{email}")
    fun resolveByEmail(@PathVariable email: String): UserDiscoveryDto {
        // Try exact match first
    var user = users.findByEmail(email)
    
    // If no exact match, try prefix match
    if (user == null) {
        val matchingUsers = users.findAll().filter { u ->
            u.email.substringBefore("@").equals(email, ignoreCase = true)
        }
        user = matchingUsers.firstOrNull()
    }
    
    if (user == null) {
        throw UserNotFoundException("No user found with email: $email")
    }
        return UserDiscoveryDto(
            userId = user.id.toString(),
            username = user.displayName,
            displayName = user.displayName,
            email = user.email,
            hasAvatar = user.profilePicture != null
        )
    }
    
    /**
     * Batch resolve by email addresses
     */
    @PostMapping("/resolve-batch-email")
    fun resolveByEmails(@RequestBody request: BatchResolveEmailRequest): BatchResolveResponse {
        val results = request.emails.mapNotNull { email ->
            try {
                resolveByEmail(email)
            } catch (e: Exception) {
                // Skip emails that don't exist
                null
            }
        }
        
        return BatchResolveResponse(results = results)
    }
    
    /**
     * Resolve short user ID to full user info (LEGACY - for backward compatibility)
     * 
     * BLE advertisements used to broadcast only the first 8 characters of the UUID.
     * This endpoint is kept for backward compatibility.
     */
    @GetMapping("/resolve/{shortId}")
    fun resolveShortId(@PathVariable shortId: String): UserDiscoveryDto {
        // Short ID should be 8 hex characters
        if (shortId.length != 8 || !shortId.matches(Regex("[0-9a-f]{8}"))) {
            throw IllegalArgumentException("Invalid short ID format")
        }
        
        // Search for user whose UUID starts with this short ID
        val matchingUsers = users.findAll().filter { user ->
            user.id.toString().replace("-", "").startsWith(shortId, ignoreCase = true)
        }
        
        if (matchingUsers.isEmpty()) {
            throw UserNotFoundException("No user found with short ID: $shortId")
        }
        
        if (matchingUsers.size > 1) {
            throw MultipleUsersFoundException(
                "Multiple users found with short ID: $shortId"
            )
        }
        
        val user = matchingUsers.first()
        
        return UserDiscoveryDto(
            userId = user.id.toString(),
            username = user.displayName,
            displayName = user.displayName,
            email = user.email,
            hasAvatar = user.profilePicture != null
        )
    }
    
    /**
     * Get multiple user info from short IDs (batch endpoint - LEGACY)
     */
    @PostMapping("/resolve-batch")
    fun resolveShortIds(@RequestBody request: BatchResolveRequest): BatchResolveResponse {
        val results = request.shortIds.mapNotNull { shortId ->
            try {
                resolveShortId(shortId)
            } catch (e: Exception) {
                null
            }
        }
        
        return BatchResolveResponse(
            results = results
        )
    }
    
    /**
     * Search users by display name
     */
    @GetMapping("/search")
    fun searchUsers(
        @RequestParam("query") query: String,
        @RequestParam("limit", defaultValue = "10") limit: Int
    ): List<UserDiscoveryDto> {
        if (query.length < 2) {
            throw IllegalArgumentException("Query must be at least 2 characters")
        }
        
        // Filter users by displayName containing query (case-insensitive)
        val results = users.findAll()
            .filter { user ->
                user.displayName.contains(query, ignoreCase = true)
            }
            .take(limit.coerceAtMost(50))
        
        return results.map { user ->
            UserDiscoveryDto(
                userId = user.id.toString(),
                username = user.displayName,
                displayName = user.displayName,
                email = user.email,
                hasAvatar = user.profilePicture != null
            )
        }
    }
}

// ========== DTOs ==========

data class UserDiscoveryDto(
    val userId: String,
    val username: String,
    val displayName: String,
    val email: String,
    val hasAvatar: Boolean
)

data class BatchResolveRequest(
    val shortIds: List<String>
)

data class BatchResolveEmailRequest(
    val emails: List<String>
)

data class BatchResolveResponse(
    val results: List<UserDiscoveryDto>
)

// ========== Exceptions ==========

class UserNotFoundException(message: String) : RuntimeException(message)

class MultipleUsersFoundException(message: String) : RuntimeException(message)