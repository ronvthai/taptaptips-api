package com.taptaptips.server.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/** Attribute key used to pass the verified user ID to downstream filters (e.g. RateLimitFilter). */
const val ATTR_VERIFIED_USER_ID = "ttt.verifiedUserId"

@Component
class JwtAuthFilter(
    private val jwt: JwtService,
    private val suspensionGuard: SuspensionGuard
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

        if (bearer != null) {
            val uid = try {
                jwt.parse(bearer)
            } catch (e: Exception) {
                // Invalid / expired token — SecurityContext stays empty and Spring Security
                // will reject the request at the authorizeHttpRequests layer.
                log.debug("JWT validation failed: ${e.message}")
                null
            }

            // A suspended user's existing token stops working right away (within
            // SuspensionGuard's cache time), not when it expires. 401 makes the
            // apps drop the session and show login, where the login endpoint
            // explains the suspension.
            if (uid != null && suspensionGuard.isSuspended(uid)) {
                log.warn("🚫 Blocked request from suspended user $uid: ${req.method} ${req.requestURI}")
                res.status = HttpServletResponse.SC_UNAUTHORIZED
                res.contentType = "application/json"
                res.writer.write(
                    """{"error":"Account suspended","message":"This account has been suspended. Contact support@taptaptips.com if you think this is a mistake."}"""
                )
                return
            }

            if (uid != null) {
                val auth = UsernamePasswordAuthenticationToken(
                    uid.toString(),
                    null,
                    emptyList()
                )
                SecurityContextHolder.getContext().authentication = auth

                // Store verified user ID so RateLimitFilter can use it without re-parsing the JWT
                req.setAttribute(ATTR_VERIFIED_USER_ID, uid.toString())
            }
        }

        chain.doFilter(req, res)
    }
}
