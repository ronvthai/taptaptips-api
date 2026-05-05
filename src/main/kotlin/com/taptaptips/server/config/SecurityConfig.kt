package com.taptaptips.server.config

import com.taptaptips.server.security.JwtAuthFilter
import com.taptaptips.server.security.RateLimitFilter
import org.springframework.beans.factory.annotation.Value
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
    private val jwtAuthFilter: JwtAuthFilter,
    private val rateLimitFilter: RateLimitFilter,

    // Set APP_ALLOWED_ORIGINS in Render env vars, comma-separated.
    // Example: "https://taptaptips.com,https://connect.stripe.com"
    @Value("\${app.cors.allowed-origins:https://taptaptips.com}")
    private val rawAllowedOrigins: String
) {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .cors { it.configurationSource(corsConfigurationSource()) }   // ← strict allowlist, not disabled
            .sessionManagement {
                it.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            }
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers("/webhooks/**").permitAll()
                    .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                    .requestMatchers("/.well-known/**").permitAll()
                    .requestMatchers(HttpMethod.POST, "/auth/register", "/auth/login").permitAll()
                    .requestMatchers(HttpMethod.POST, "/auth/password/forgot").permitAll()
                    .requestMatchers(HttpMethod.POST, "/auth/password/validate-token").permitAll()
                    .requestMatchers(HttpMethod.POST, "/auth/password/reset").permitAll()
                    .requestMatchers(HttpMethod.GET, "/reset-password").permitAll()
                    .requestMatchers(HttpMethod.GET, "/stripe/onboarding/complete").permitAll()
                    .requestMatchers(HttpMethod.GET, "/stripe/onboarding/refresh").permitAll()
                    // Public endpoint — mobile app fetches the Stripe publishable key
                    // before presenting payment UI, with no auth token available yet.
                    .requestMatchers(HttpMethod.GET, "/stripe/config").permitAll()
                    .anyRequest().authenticated()
            }
            .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter::class.java)
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter::class.java)

        return http.build()
    }

    /**
     * CORS is enabled with an explicit allowlist.
     * The mobile app does not use CORS (native HTTP), but the Stripe redirect
     * pages, password-reset web flow, and any future web dashboard do.
     *
     * Configure APP_ALLOWED_ORIGINS in Render to add/remove origins without
     * touching code.
     */
    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val allowedOrigins = rawAllowedOrigins
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val config = CorsConfiguration().apply {
            this.allowedOrigins = allowedOrigins
            allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            allowedHeaders = listOf("Authorization", "Content-Type", "X-Requested-With")
            exposedHeaders = listOf("X-RateLimit-Remaining", "Retry-After")
            allowCredentials = false   // no cookies — JWT in Authorization header only
            maxAge = 3600L
        }

        return UrlBasedCorsConfigurationSource().apply {
            registerCorsConfiguration("/**", config)
        }
    }

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()
}