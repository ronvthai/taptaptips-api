package com.taptaptips.server.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "jwt")
data class JwtProps(
    val issuer: String,
    val accessTtlMinutes: Long,
    val refreshTtlDays: Long,
    val secret: String,
)
