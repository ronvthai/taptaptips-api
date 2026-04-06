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
    private val rateLimiterService: RateLimiterService,
    private val jwt: JwtService
) : OncePerRequestFilter() {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val path = request.requestURI
        val method = request.method

        // Skip rate limiting for webhooks, health checks, and App Links verification
        if (path.startsWith("/webhooks/") ||
            path.startsWith("/actuator/") ||
            path.startsWith("/.well-known/")) {
            filterChain.doFilter(request, response)
            return
        }

        // Login: only count failed attempts
        if (path == "/auth/login" && method == "POST") {
            handleLoginWithPostCheckRateLimit(request, response, filterChain)
            return
        }

        // Logout is never rate-limited
        if (path == "/auth/logout" && method == "POST") {
            filterChain.doFilter(request, response)
            return
        }

        handleStandardRateLimit(request, response, filterChain, path, method)
    }

    private fun handleLoginWithPostCheckRateLimit(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val ip = getClientIp(request)
        val bucket = rateLimiterService.resolveLoginBucket(ip)

        val availableTokens = bucket.availableTokens

        if (availableTokens <= 0) {
            val probe = bucket.tryConsumeAndReturnRemaining(1)
            val retryAfterSeconds = probe.nanosToWaitForRefill / 1_000_000_000

            logger.warn("⚠️ Login rate limit exceeded: ip=${ip.take(10)}..., retryAfter=${retryAfterSeconds}s")

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

        val responseWrapper = ContentCachingResponseWrapper(response)
        filterChain.doFilter(request, responseWrapper)

        val statusCode = responseWrapper.status

        if (statusCode == HttpStatus.UNAUTHORIZED.value()) {
            bucket.tryConsumeAndReturnRemaining(1)
            logger.info("🔒 Failed login attempt from $ip - consumed rate limit token (${bucket.availableTokens} remaining)")
        } else if (statusCode == HttpStatus.OK.value()) {
            rateLimiterService.resetLoginBucket(ip)
            logger.info("✅ Successful login from $ip - rate limit bucket reset")
        }

        responseWrapper.copyBodyToResponse()
    }

    private fun handleStandardRateLimit(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
        path: String,
        method: String
    ) {
        val bucketInfo = determineRateLimitBucket(request, path, method) ?: run {
            filterChain.doFilter(request, response)
            return
        }

        val probe = bucketInfo.bucket.tryConsumeAndReturnRemaining(1)

        if (probe.isConsumed) {
            response.setHeader("X-RateLimit-Remaining", probe.remainingTokens.toString())
            filterChain.doFilter(request, response)
        } else {
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

    private fun determineRateLimitBucket(
        request: HttpServletRequest,
        path: String,
        method: String
    ): RateLimitInfo? {
        return when {
            path == "/auth/register" && method == "POST" -> {
                val ip = getClientIp(request)
                RateLimitInfo(
                    bucket = rateLimiterService.resolveRegistrationBucket(ip),
                    key = ip,
                    type = "registration",
                    userMessage = "Too many registration attempts. Please wait before trying again."
                )
            }

            path == "/auth/password/forgot" && method == "POST" -> {
                val ip = getClientIp(request)
                RateLimitInfo(
                    bucket = rateLimiterService.resolvePasswordResetBucket(ip),
                    key = ip,
                    type = "password-reset-request",
                    userMessage = "Too many reset attempts. Please wait before requesting another reset link."
                )
            }

            path == "/auth/password/reset" && method == "POST" -> {
                val ip = getClientIp(request)
                RateLimitInfo(
                    bucket = rateLimiterService.resolvePasswordResetCompletionBucket(ip),
                    key = ip,
                    type = "password-reset-completion",
                    userMessage = "Too many password reset attempts. Please wait before trying again."
                )
            }

            path == "/auth/password/validate-token" && method == "POST" -> {
                val ip = getClientIp(request)
                RateLimitInfo(
                    bucket = rateLimiterService.resolveTokenValidationBucket(ip),
                    key = ip,
                    type = "token-validation",
                    userMessage = "Too many validation attempts. Please wait before trying again."
                )
            }

            // BLE token issuance: dedicated tight bucket (4 per 30 min per user)
            path == "/ble/token" && method == "POST" -> {
                val userId = parseUserIdFromJwt(request) ?: return null
                RateLimitInfo(
                    bucket = rateLimiterService.resolveBleBucket(userId),
                    key = userId,
                    type = "ble-token",
                    userMessage = "Too many token requests. Please wait before requesting a new BLE token."
                )
            }

            // Tip creation: bucket keyed on verified user ID
            path.startsWith("/tips") && method == "POST" -> {
                val userId = parseUserIdFromJwt(request) ?: return null
                RateLimitInfo(
                    bucket = rateLimiterService.resolveTipCreationBucket(userId),
                    key = userId,
                    type = "tip-creation",
                    userMessage = "Too many tips created. Please wait before sending another tip."
                )
            }

            // General authenticated API: bucket keyed on verified user ID
            else -> {
                val userId = parseUserIdFromJwt(request) ?: return null
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
     * Returns the verified user ID for rate-limit keying.
     *
     * JwtAuthFilter runs before this filter and stores the parsed UUID as a
     * request attribute [ATTR_VERIFIED_USER_ID] — we read that directly to
     * avoid a second JWT signature verification on every request.
     */
    private fun parseUserIdFromJwt(request: HttpServletRequest): String? {
        val cached = request.getAttribute(ATTR_VERIFIED_USER_ID)
        if (cached is String) return cached

        // Fallback if attribute is missing (should not happen in normal flow)
        val authHeader = request.getHeader("Authorization")
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return null
        return try {
            jwt.parse(authHeader.removePrefix("Bearer ").trim()).toString()
        } catch (e: Exception) {
            null
        }
    }

    private fun formatRetryTime(seconds: Long): String = when {
        seconds < 60   -> "$seconds seconds"
        seconds < 3600 -> "${seconds / 60} minutes"
        else           -> "${seconds / 3600} hours"
    }

    private fun getClientIp(request: HttpServletRequest): String {
        val xForwardedFor = request.getHeader("X-Forwarded-For")
        if (!xForwardedFor.isNullOrBlank()) return xForwardedFor.split(",").first().trim()

        val xRealIp = request.getHeader("X-Real-IP")
        if (!xRealIp.isNullOrBlank()) return xRealIp

        return request.remoteAddr
    }

    private data class RateLimitInfo(
        val bucket: io.github.bucket4j.Bucket,
        val key: String,
        val type: String,
        val userMessage: String
    )
}
