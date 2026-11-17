package com.taptaptips.server.security

import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * The caller's real IP, for rate limits, lockouts and logs.
 *
 * Same rules as [RateLimitFilter]: never trust the FIRST X-Forwarded-For entry,
 * because the client writes it. A random value per request would get a fresh
 * rate-limit bucket every time.
 *
 * Order:
 *  1. The trusted header set by the edge (CF-Connecting-IP by default,
 *     `ratelimit.client-ip-header`; blank skips this step).
 *  2. The LAST X-Forwarded-For entry (appended by the proxy closest to us).
 *  3. The socket address.
 */
@Component
class ClientIp(
    @Value("\${ratelimit.client-ip-header:CF-Connecting-IP}")
    private val trustedIpHeader: String
) {
    fun of(request: HttpServletRequest): String {
        if (trustedIpHeader.isNotBlank()) {
            val trusted = request.getHeader(trustedIpHeader)?.trim()
            if (!trusted.isNullOrBlank() && looksLikeIp(trusted)) return trusted
        }
        val xff = request.getHeader("X-Forwarded-For")
        if (!xff.isNullOrBlank()) {
            val last = xff.split(",").map { it.trim() }.lastOrNull { it.isNotEmpty() }
            if (last != null && looksLikeIp(last)) return last
        }
        return request.remoteAddr ?: "unknown"
    }

    private fun looksLikeIp(v: String): Boolean =
        v.length <= 45 && v.all { it.isLetterOrDigit() || it == '.' || it == ':' }
}
