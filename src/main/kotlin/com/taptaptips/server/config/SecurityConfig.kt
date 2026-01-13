package com.taptaptips.server.config

import com.taptaptips.server.security.JwtAuthFilter
import com.taptaptips.server.security.RateLimitFilter
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

@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val jwtAuthFilter: JwtAuthFilter,
    private val rateLimitFilter: RateLimitFilter
) {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .cors { it.disable() }
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
                    .anyRequest().authenticated()
            }
            .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter::class.java)
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter::class.java)

        return http.build()
    }
    
    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()
}