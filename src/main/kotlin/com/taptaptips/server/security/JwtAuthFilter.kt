package com.taptaptips.server.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class JwtAuthFilter(
    private val jwt: JwtService
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(JwtAuthFilter::class.java)

    override fun doFilterInternal(
        req: HttpServletRequest,
        res: HttpServletResponse,
        chain: FilterChain
    ) {
        val bearer = req.getHeader("Authorization")
            ?.takeIf { it.startsWith("Bearer ") }
            ?.removePrefix("Bearer ")
            ?.trim()
        // 🔍 DEBUG: Log SSE connection attempts
        if (req.requestURI.contains("/notifications/stream")) {
            log.info("🔍 SSE connection attempt from ${req.remoteAddr}")
            log.info("🔍 Has Authorization header: ${bearer != null}")
            log.info("🔍 Token length: ${bearer?.length}")
        }
        if (bearer != null) {
            try {
                val uid = jwt.parse(bearer)
                val auth = UsernamePasswordAuthenticationToken(
                    uid.toString(),
                    null,
                    emptyList()
                )
                SecurityContextHolder.getContext().authentication = auth
                // 🔍 DEBUG: Log successful auth
                if (req.requestURI.contains("/notifications/stream")) {
                    log.info("✅ JWT validated for user: $uid")
                }
            } catch (e: Exception) {
                if (req.requestURI.contains("/notifications/stream")) {
                    log.error("❌ JWT validation failed for SSE: ${e.message}", e)
                } else {
                    log.debug("JWT validation failed: ${e.message}")
                }
            }
        }

        chain.doFilter(req, res)
    }
}
