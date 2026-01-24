package com.taptaptips.server.security

import com.taptaptips.server.service.RateLimiterService
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.ContentCachingResponseWrapper

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
        
        // Special handling for login - only rate limit failed attempts
        if (path == "/auth/login" && method == "POST") {
            handleLoginWithPostCheckRateLimit(request, response, filterChain)
            return
        }
        
        // Logout should never be rate limited
        if (path == "/auth/logout" && method == "POST") {
            filterChain.doFilter(request, response)
            return
        }
        
        // Handle all other endpoints with standard rate limiting
        handleStandardRateLimit(request, response, filterChain, path, method)
    }
    
    /**
     * Handle login with post-request rate limiting
     * Only consume tokens if login fails (401 status)
     */
    private fun handleLoginWithPostCheckRateLimit(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val ip = getClientIp(request)
        val bucket = rateLimiterService.resolveLoginBucket(ip)
        
        // ✅ FIX: Check available tokens properly
        val availableTokens = bucket.availableTokens
        
        if (availableTokens <= 0) {
            // ❌ Rate limit already exceeded - block immediately
            val probe = bucket.tryConsumeAndReturnRemaining(1)
            val retryAfterSeconds = probe.nanosToWaitForRefill / 1_000_000_000
            
            logger.warn(
                "⚠️ Login rate limit exceeded: " +
                "ip=${ip.take(10)}..., " +
                "retryAfter=${retryAfterSeconds}s"
            )
            
            response.status = HttpStatus.TOO_MANY_REQUESTS.value()
            response.contentType = "application/json; charset=UTF-8"
            response.setHeader("Retry-After", retryAfterSeconds.toString())
            response.setHeader("X-RateLimit-Limit", "5")
            response.setHeader("X-RateLimit-Remaining", "0")
            response.setHeader("X-RateLimit-Reset", (System.currentTimeMillis() / 1000 + retryAfterSeconds).toString())
            
            response.writer.write("""
                {
                    "error": "Rate limit exceeded",
                    "message": "Too many failed login attempts. Please wait before trying again.",
                    "retryAfter": $retryAfterSeconds,
                    "retryAfterFormatted": "${formatRetryTime(retryAfterSeconds)}",
                    "type": "login",
                    "timestamp": ${System.currentTimeMillis()}
                }
            """.trimIndent())
            return
        }
        
        // Wrap response to capture status code
        val responseWrapper = ContentCachingResponseWrapper(response)
        
        // Allow the request to proceed
        filterChain.doFilter(request, responseWrapper)
        
        // After the request is processed, check if login failed
        val statusCode = responseWrapper.status
        
        if (statusCode == HttpStatus.UNAUTHORIZED.value()) {
            // ❌ Login failed (401) - consume a token
            bucket.tryConsumeAndReturnRemaining(1)
            val remainingTokens = bucket.availableTokens
            logger.info("🔒 Failed login attempt from $ip - consumed rate limit token ($remainingTokens remaining)")
        } else if (statusCode == HttpStatus.OK.value()) {
            // ✅ Login successful (200) - reset the bucket
            rateLimiterService.resetLoginBucket(ip)
            logger.info("✅ Successful login from $ip - rate limit bucket reset")
        }
        // For other status codes (400, 500, etc.), don't consume token
        
        // Copy the cached response back to original response
        responseWrapper.copyBodyToResponse()
    }
    
    /**
     * Handle standard rate limiting (consume token before request)
     */
    private fun handleStandardRateLimit(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
        path: String,
        method: String
    ) {
        val bucketInfo = determineRateLimitBucket(request, path, method) ?: run {
            // No rate limiting needed (public endpoint, etc.)
            filterChain.doFilter(request, response)
            return
        }
        
        val probe = bucketInfo.bucket.tryConsumeAndReturnRemaining(1)
        
        if (probe.isConsumed) {
            // Request allowed
            response.setHeader("X-RateLimit-Remaining", probe.remainingTokens.toString())
            filterChain.doFilter(request, response)
        } else {
            // Rate limit exceeded
            val retryAfterSeconds = probe.nanosToWaitForRefill / 1_000_000_000
            
            logger.warn(
                "⚠️ Rate limit exceeded: type=${bucketInfo.type}, " +
                "key=${bucketInfo.key.take(10)}..., path=$path, method=$method"
            )
            
            response.status = HttpStatus.TOO_MANY_REQUESTS.value()
            response.contentType = "application/json; charset=UTF-8"
            response.setHeader("Retry-After", retryAfterSeconds.toString())
            response.setHeader("X-RateLimit-Limit", bucketInfo.bucket.availableTokens.toString())
            response.setHeader("X-RateLimit-Remaining", "0")
            response.setHeader("X-RateLimit-Reset", (System.currentTimeMillis() / 1000 + retryAfterSeconds).toString())
            
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
     * Determine which rate limit bucket to use
     */
    private fun determineRateLimitBucket(
        request: HttpServletRequest,
        path: String,
        method: String
    ): RateLimitInfo? {
        return when {
            // Registration endpoint
            path == "/auth/register" && method == "POST" -> {
                val ip = getClientIp(request)
                RateLimitInfo(
                    bucket = rateLimiterService.resolveRegistrationBucket(ip),
                    key = ip,
                    type = "registration",
                    userMessage = "Too many registration attempts. Please wait before trying again."
                )
            }
            
            // Password reset request
            path == "/auth/password/forgot" && method == "POST" -> {
                val ip = getClientIp(request)
                RateLimitInfo(
                    bucket = rateLimiterService.resolvePasswordResetBucket(ip),
                    key = ip,
                    type = "password-reset-request",
                    userMessage = "Too many reset attempts. Please wait before requesting another reset link."
                )
            }
            
            // Password reset completion
            path == "/auth/password/reset" && method == "POST" -> {
                val ip = getClientIp(request)
                RateLimitInfo(
                    bucket = rateLimiterService.resolvePasswordResetCompletionBucket(ip),
                    key = ip,
                    type = "password-reset-completion",
                    userMessage = "Too many password reset attempts. Please wait before trying again."
                )
            }
            
            // Token validation
            path == "/auth/password/validate-token" && method == "POST" -> {
                val ip = getClientIp(request)
                RateLimitInfo(
                    bucket = rateLimiterService.resolveTokenValidationBucket(ip),
                    key = ip,
                    type = "token-validation",
                    userMessage = "Too many validation attempts. Please wait before trying again."
                )
            }
            
            // Tip creation
            path.startsWith("/tips") && method == "POST" -> {
                val userId = getUserIdFromJwt(request) ?: return null
                RateLimitInfo(
                    bucket = rateLimiterService.resolveTipCreationBucket(userId),
                    key = userId,
                    type = "tip-creation",
                    userMessage = "Too many tips created. Please wait before sending another tip."
                )
            }
            
            // General API calls (authenticated)
            else -> {
                val userId = getUserIdFromJwt(request) ?: return null
                RateLimitInfo(
                    bucket = rateLimiterService.resolveGeneralApiBucket(userId),
                    key = userId,
                    type = "api",
                    userMessage = "Too many requests. Please wait before trying again."
                )
            }
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
        val xForwardedFor = request.getHeader("X-Forwarded-For")
        if (!xForwardedFor.isNullOrBlank()) {
            return xForwardedFor.split(",").first().trim()
        }
        
        val xRealIp = request.getHeader("X-Real-IP")
        if (!xRealIp.isNullOrBlank()) {
            return xRealIp
        }
        
        return request.remoteAddr
    }
    
    /**
     * Extract user ID from JWT token
     */
    private fun getUserIdFromJwt(request: HttpServletRequest): String? {
        val authHeader = request.getHeader("Authorization")
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null
        }
        
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