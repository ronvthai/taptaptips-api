package com.taptaptips.server.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.web.SecurityFilterChain

@Configuration
@EnableWebSecurity
class StaticResourcesSecurityConfig {
    
    @Bean
    fun staticResourcesFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .securityMatcher("/.well-known/**")
            .authorizeHttpRequests { auth ->
                auth.anyRequest().permitAll()
            }
            .csrf { it.disable() }
        
        return http.build()
    }
}