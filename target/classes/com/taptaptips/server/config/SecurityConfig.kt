package com.taptaptips.server.config

import com.taptaptips.server.security.JwtAuthFilter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
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
            .csrf { it.disable() }
            .cors { }
            .sessionManagement {
                it.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            }
            .authorizeHttpRequests { auth ->
                auth
                    // Health / info for Render health checks
                    .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                    // Auth endpoints (adjust to match your app)
                    .requestMatchers(HttpMethod.POST, "/auth/register", "/auth/login").permitAll()
                    // WebSocket handshake endpoint if you use STOMP
                    .requestMatchers("/ws/**").permitAll()
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
            c.allowedOriginPatterns = listOf("*") // dev only; tighten for prod
            c.allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            c.allowedHeaders = listOf("*")
            c.allowCredentials = true
            registerCorsConfiguration("/**", c)
        }
}
