package com.taptaptips.server.security

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.*
import java.util.UUID

@Service
open class JwtService(
    @Value("\${jwt.secret}") private val secret: String,
    @Value("\${jwt.issuer}") private val issuer: String,
    @Value("\${jwt.accessTtlMinutes}") private val accessTtl: Long
) {
    private val key = Keys.hmacShaKeyFor(secret.toByteArray())

    fun issueAccess(userId: UUID): String = Jwts.builder()
        .subject(userId.toString())
        .issuer(issuer)
        .issuedAt(Date())
        .expiration(Date(System.currentTimeMillis() + accessTtl * 60 * 1000))
        .signWith(key)
        .compact()

    fun parse(token: String): UUID =
        UUID.fromString(Jwts.parser().verifyWith(key).build().parseSignedClaims(token).payload.subject)
}
