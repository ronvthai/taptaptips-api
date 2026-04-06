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

        if (bearer != null) {
            try {
                val uid = jwt.parse(bearer)
                val auth = UsernamePasswordAuthenticationToken(
                    uid.toString(),
                    null,
                    emptyList()
                )
                SecurityContextHolder.getContext().authentication = auth

                // Store verified user ID so RateLimitFilter can use it without re-parsing the JWT
                req.setAttribute(ATTR_VERIFIED_USER_ID, uid.toString())

            } catch (e: Exception) {
                // Invalid / expired token — SecurityContext stays empty and Spring Security
                // will reject the request at the authorizeHttpRequests layer.
                log.debug("JWT validation failed: ${e.message}")
            }
        }

        chain.doFilter(req, res)
    }
}
