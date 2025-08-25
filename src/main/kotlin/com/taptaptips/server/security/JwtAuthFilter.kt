package com.taptaptips.server.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

class JwtAuthFilter(private val jwt: JwtService) : OncePerRequestFilter() {
    override fun doFilterInternal(req: HttpServletRequest, res: HttpServletResponse, chain: FilterChain) {
        val bearer = req.getHeader("Authorization")?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")
        if (bearer != null) {
            try {
                val uid = jwt.parse(bearer)
                val auth = UsernamePasswordAuthenticationToken(uid.toString(), null, listOf())
                SecurityContextHolder.getContext().authentication = auth
            } catch (_: Exception) { }
        }
        chain.doFilter(req, res)
    }
}
