// src/main/kotlin/com/taptaptips/server/security/JwtAuthFilter.kt
package com.taptaptips.server.security

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.nio.charset.StandardCharsets
import java.util.*

@Component
class JwtAuthFilter(
    @Value("\${JWT_SECRET}") private val jwtSecret: String
) : OncePerRequestFilter() {

    override fun doFilterInternal(req: HttpServletRequest, res: HttpServletResponse, chain: FilterChain) {
        val header = req.getHeader("Authorization")
        if (header?.startsWith("Bearer ") == true) {
            val token = header.substring(7)
            try {
                val key = Keys.hmacShaKeyFor(jwtSecret.toByteArray(StandardCharsets.UTF_8))
                val claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).payload
                val sub = claims.subject   // expected to be your user UUID string
                if (!sub.isNullOrBlank()) {
                    val auth = object : AbstractAuthenticationToken(listOf(SimpleGrantedAuthority("ROLE_USER"))) {
                        override fun getCredentials() = token
                        override fun getPrincipal() = sub
                        override fun getName() = sub
                        init { isAuthenticated = true }
                    }
                    SecurityContextHolder.getContext().authentication = auth
                }
            } catch (_: Exception) {
                // Invalid/expired token -> leave context unauthenticated
            }
        }
        chain.doFilter(req, res)
    }
}
