package com.taptaptips.server.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "crypto.bank")
data class CryptoBankProps(
    val keyBase64: String
)
