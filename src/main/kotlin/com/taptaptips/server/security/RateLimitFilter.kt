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
                Triple(
                    rateLimiterService.resolveLoginBucket(ip),
                    ip,
                    "login"
                )
            }
            
            // Password reset: limit by IP address
            // (ideally by email, but we'd need to parse request body)
            path == "/auth/password/forgot" && method == "POST" -> {
                val ip = getClientIp(request)
                Triple(
                    rateLimiterService.resolvePasswordResetBucket(ip),
                    ip,
                    "password-reset"
                )
            }
            
            // Tip creation: limit by user ID (from JWT)
            path.startsWith("/tips") && method == "POST" -> {
                val userId = getUserIdFromJwt(request)
                if (userId != null) {
                    Triple(
                        rateLimiterService.resolveTipCreationBucket(userId),
                        userId,
                        "tip-creation"
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
                    Triple(
                        rateLimiterService.resolveGeneralApiBucket(userId),
                        userId,
                        "api"
                    )
                } else {
                    // Public endpoint or not authenticated yet
                    filterChain.doFilter(request, response)
                    return
                }
            }
        }
        
        val (bucket, key, limitType) = bucketInfo
        
        // Try to consume 1 token from the bucket
        val probe = bucket.tryConsumeAndReturnRemaining(1)
        
        if (probe.isConsumed) {
            // ✅ Request allowed - token available
            // Add rate limit headers to inform client
            response.setHeader("X-RateLimit-Remaining", probe.remainingTokens.toString())
            
            filterChain.doFilter(request, response)
        } else {
            // ❌ Rate limit exceeded
            val retryAfterSeconds = probe.nanosToWaitForRefill / 1_000_000_000
            
            logger.warn("Rate limit exceeded: type=$limitType, key=${key.take(10)}..., path=$path, retryAfter=${retryAfterSeconds}s"
)
            
            // Return 429 Too Many Requests
            response.status = HttpStatus.TOO_MANY_REQUESTS.value()
            response.contentType = "application/json"
            response.setHeader("Retry-After", retryAfterSeconds.toString())
            response.setHeader("X-RateLimit-Remaining", "0")
            
            response.writer.write("""
                {
                    "error": "Rate limit exceeded",
                    "message": "Too many requests. Please try again later.",
                    "retryAfter": $retryAfterSeconds
                }
            """.trimIndent())
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
}