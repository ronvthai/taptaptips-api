package com.taptaptips.server.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(JwtProps::class, CryptoBankProps::class)
class AppConfig
