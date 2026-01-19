package com.taptaptips.server.security

import com.taptaptips.server.service.RateLimiterService
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class RateLimitFilter(
    private val rateLimiterService: RateLimiterService
) : OncePerRequestFilter() {
    
    private val logger = LoggerFactory.getLogger(javaClass)
    
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val path = request.requestURI
        val method = request.method
        
        // Skip rate limiting for:
        // - Stripe webhooks (critical, can't be delayed)
        // - Health checks (monitoring services)
        // - Android App Links verification
        if (path.startsWith("/webhooks/") || 
            path.startsWith("/actuator/") ||
            path.startsWith("/.well-known/")) {
            filterChain.doFilter(request, response)
            return
        }
        
        // Determine which rate limit bucket to use based on endpoint
        val bucketInfo = when {
            // Login endpoint: limit by IP address
            path == "/auth/login" && method == "POST" -> {
                val ip = getClientIp(request)
                RateLimitInfo(
                    bucket = rateLimiterService.resolveLoginBucket(ip),
                    key = ip,
                    type = "login",
                    userMessage = "Too many login attempts. Please wait before trying again."
                )
            }
            
            // Registration endpoint: limit by IP address
            path == "/auth/register" && method == "POST" -> {
                val ip = getClientIp(request)
                RateLimitInfo(
                    bucket = rateLimiterService.resolveRegistrationBucket(ip),
                    key = ip,
                    type = "registration",
                    userMessage = "Too many registration attempts. Please wait before trying again."
                )
            }
            
            // Password reset request: limit by IP address
            path == "/auth/password/forgot" && method == "POST" -> {
                val ip = getClientIp(request)
                RateLimitInfo(
                    bucket = rateLimiterService.resolvePasswordResetBucket(ip),
                    key = ip,
                    type = "password-reset-request",
                    userMessage = "Too many reset attempts. Please wait before requesting another reset link."
                )
            }
            
            // Password reset completion: limit by IP address
            path == "/auth/password/reset" && method == "POST" -> {
                val ip = getClientIp(request)
                RateLimitInfo(
                    bucket = rateLimiterService.resolvePasswordResetCompletionBucket(ip),
                    key = ip,
                    type = "password-reset-completion",
                    userMessage = "Too many password reset attempts. Please wait before trying again."
                )
            }
            
            // Token validation: limit by IP address
            path == "/auth/password/validate-token" && method == "POST" -> {
                val ip = getClientIp(request)
                RateLimitInfo(
                    bucket = rateLimiterService.resolveTokenValidationBucket(ip),
                    key = ip,
                    type = "token-validation",
                    userMessage = "Too many validation attempts. Please wait before trying again."
                )
            }
            
            // Tip creation: limit by user ID (from JWT)
            path.startsWith("/tips") && method == "POST" -> {
                val userId = getUserIdFromJwt(request)
                if (userId != null) {
                    RateLimitInfo(
                        bucket = rateLimiterService.resolveTipCreationBucket(userId),
                        key = userId,
                        type = "tip-creation",
                        userMessage = "Too many tips created. Please wait before sending another tip."
                    )
                } else {
                    // No JWT = not authenticated yet, security filter will block
                    filterChain.doFilter(request, response)
                    return
                }
            }
            
            // All other authenticated endpoints: general rate limit by user
            else -> {
                val userId = getUserIdFromJwt(request)
                if (userId != null) {
                    RateLimitInfo(
                        bucket = rateLimiterService.resolveGeneralApiBucket(userId),
                        key = userId,
                        type = "api",
                        userMessage = "Too many requests. Please wait before trying again."
                    )
                } else {
                    // Public endpoint or not authenticated yet
                    filterChain.doFilter(request, response)
                    return
                }
            }
        }
        
        val probe = bucketInfo.bucket.tryConsumeAndReturnRemaining(1)
        
        if (probe.isConsumed) {
            // ✅ Request allowed - token available
            // Add rate limit headers to inform client
            response.setHeader("X-RateLimit-Remaining", probe.remainingTokens.toString())
            
            filterChain.doFilter(request, response)
        } else {
            // ❌ Rate limit exceeded
            val retryAfterSeconds = probe.nanosToWaitForRefill / 1_000_000_000
            
            logger.warn(
                "⚠️ Rate limit exceeded: " +
                "type=${bucketInfo.type}, " +
                "key=${bucketInfo.key.take(10)}..., " +
                "path=$path, " +
                "method=$method, " +
                "retryAfter=${retryAfterSeconds}s"
            )
            
            // Return 429 Too Many Requests with proper headers
            response.status = HttpStatus.TOO_MANY_REQUESTS.value()
            response.contentType = "application/json; charset=UTF-8"
            
            // Standard Retry-After header (in seconds)
            response.setHeader("Retry-After", retryAfterSeconds.toString())
            
            // Additional headers for debugging
            response.setHeader("X-RateLimit-Limit", bucketInfo.bucket.availableTokens.toString())
            response.setHeader("X-RateLimit-Remaining", "0")
            response.setHeader("X-RateLimit-Reset", (System.currentTimeMillis() / 1000 + retryAfterSeconds).toString())
            
            // Return user-friendly error message
            response.writer.write("""
                {
                    "error": "Rate limit exceeded",
                    "message": "${bucketInfo.userMessage}",
                    "retryAfter": $retryAfterSeconds,
                    "retryAfterFormatted": "${formatRetryTime(retryAfterSeconds)}",
                    "type": "${bucketInfo.type}",
                    "timestamp": ${System.currentTimeMillis()}
                }
            """.trimIndent())
        }
    }
    
    /**
     * Format retry time into human-readable string
     */
    private fun formatRetryTime(seconds: Long): String {
        return when {
            seconds < 60 -> "$seconds seconds"
            seconds < 3600 -> "${seconds / 60} minutes"
            else -> "${seconds / 3600} hours"
        }
    }
    
    /**
     * Get client IP address, handling proxies and load balancers
     */
    private fun getClientIp(request: HttpServletRequest): String {
        // Check X-Forwarded-For header (set by proxies like Render)
        val xForwardedFor = request.getHeader("X-Forwarded-For")
        if (!xForwardedFor.isNullOrBlank()) {
            // Can be comma-separated if multiple proxies
            return xForwardedFor.split(",").first().trim()
        }
        
        // Check X-Real-IP header (alternative header)
        val xRealIp = request.getHeader("X-Real-IP")
        if (!xRealIp.isNullOrBlank()) {
            return xRealIp
        }
        
        // Fall back to direct remote address
        return request.remoteAddr
    }
    
    /**
     * Extract user ID from JWT token (simplified)
     * For better implementation, inject JwtService and decode properly
     */
    private fun getUserIdFromJwt(request: HttpServletRequest): String? {
        val authHeader = request.getHeader("Authorization")
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null
        }
        
        // Use first 20 chars of token as rate limit key
        // (Not perfect but works - real implementation would decode JWT)
        val token = authHeader.substring(7)
        return token.take(20)
    }
    
    /**
     * Data class to hold rate limit information
     */
    private data class RateLimitInfo(
        val bucket: io.github.bucket4j.Bucket,
        val key: String,
        val type: String,
        val userMessage: String
    )
}