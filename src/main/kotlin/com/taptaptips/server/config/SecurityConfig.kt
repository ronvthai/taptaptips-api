package com.taptaptips.server.config

import com.taptaptips.server.security.JwtAuthFilter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val jwtAuthFilter: JwtAuthFilter
) {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }  // ← FULLY DISABLE CSRF
            .cors { }
            .sessionManagement {
                it.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            }
            .authorizeHttpRequests { auth ->
                auth
                    // ============ WEBHOOKS FIRST (MOST IMPORTANT) ============
                    .requestMatchers("/webhooks/**").permitAll()
                    
                    // Health / info for Render health checks
                    .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                    
                    // Android App Links verification
                    .requestMatchers("/.well-known/**").permitAll()
                    
                    // Auth endpoints
                    .requestMatchers(HttpMethod.POST, "/auth/register", "/auth/login").permitAll()
                    
                    // Password reset endpoints (must be public)
                    .requestMatchers(HttpMethod.POST, "/auth/password/forgot").permitAll()
                    .requestMatchers(HttpMethod.POST, "/auth/password/validate-token").permitAll()
                    .requestMatchers(HttpMethod.POST, "/auth/password/reset").permitAll()
                    
                    // Stripe onboarding callbacks
                    .requestMatchers(HttpMethod.GET, "/stripe/onboarding/complete").permitAll()
                    .requestMatchers(HttpMethod.GET, "/stripe/onboarding/refresh").permitAll()
                    
                    // Stripe receiver endpoints - authenticated users only
                    .requestMatchers("/stripe/receiver/**").authenticated()
                    
                    // Avatar endpoints
                    .requestMatchers(HttpMethod.GET, "/users/*/avatar").authenticated()
                    
                    // WebSocket
                    .requestMatchers("/ws/**").permitAll()
                    
                    // SSE notifications
                    .requestMatchers("/notifications/**").authenticated()
                    
                    // Discovery endpoints
                    .requestMatchers("/discovery/**").authenticated()
                    
                    // Everything else needs JWT
                    .anyRequest().authenticated()
            }
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter::class.java)

        return http.build()
    }

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource =
        UrlBasedCorsConfigurationSource().apply {
            val c = CorsConfiguration()
            c.allowedOriginPatterns = listOf("*")
            c.allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            c.allowedHeaders = listOf("*")
            c.allowCredentials = true
            registerCorsConfiguration("/**", c)
        }
    
    @Bean
    fun passwordEncoder(): PasswordEncoder {
        return BCryptPasswordEncoder()
    }
}