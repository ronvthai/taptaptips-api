package com.taptaptips.server.config

import com.taptaptips.server.security.JwtAuthFilter
import com.taptaptips.server.security.RateLimitFilter
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
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
            // Missing / invalid / expired token → 401 (Spring's default is 403).
            // Both apps log the user out on 401, so a rejected token now ends
            // the session cleanly instead of leaving every screen failing.
            .exceptionHandling {
                it.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
            }
            .authorizeHttpRequests { auth ->
                auth
                    // Spring's error page. Uncaught errors (500s, bad JSON, 404s)
                    // are forwarded here without the user's login attached, so
                    // locking it made every server error come back as 401 —
                    // which the apps treat as "session dead" and log out.
                    .requestMatchers("/error").permitAll()
                    .requestMatchers("/webhooks/**").permitAll()
                    .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                    .requestMatchers("/.well-known/**").permitAll()
                    .requestMatchers(HttpMethod.POST, "/auth/register", "/auth/login").permitAll()
                    // Refresh-token exchange and sign-out carry no access token
                    // (it may have expired long ago) — see RefreshTokenService.
                    .requestMatchers(HttpMethod.POST, "/auth/token", "/auth/logout").permitAll()
                    .requestMatchers(HttpMethod.POST, "/auth/password/forgot").permitAll()
                    .requestMatchers(HttpMethod.POST, "/auth/password/validate-token").permitAll()
                    .requestMatchers(HttpMethod.POST, "/auth/password/reset").permitAll()
                    .requestMatchers(HttpMethod.GET, "/reset-password").permitAll()
                    .requestMatchers(HttpMethod.GET, "/stripe/onboarding/complete").permitAll()
                    .requestMatchers(HttpMethod.GET, "/stripe/onboarding/refresh").permitAll()
                    // Public endpoint — mobile app fetches the Stripe publishable key
                    // before presenting payment UI, with no auth token available yet.
                    .requestMatchers(HttpMethod.GET, "/stripe/config").permitAll()
                    // Referral program: "is it on?" (sign-up screen, before login)
                    // and the landing page referral QR codes open (/r/CODE).
                    .requestMatchers(HttpMethod.GET, "/referrals/config").permitAll()
                    .requestMatchers(HttpMethod.GET, "/r/**").permitAll()
                    .requestMatchers(HttpMethod.GET, "/t/**").permitAll()
                    .requestMatchers(HttpMethod.POST, "/t/*/intent", "/t/*/complete").permitAll()
                    // Admin settings page: has its own password check (ADMIN_PASSWORD),
                    // not the app login. See AdminController.
                    .requestMatchers(HttpMethod.GET, "/admin", "/admin/api/settings", "/admin/api/bans").permitAll()
                    .requestMatchers(HttpMethod.POST, "/admin/api/**").permitAll()
                    // Admin console (read-only reports): also its own password check
                    // (ADMIN_PASSWORD, X-Admin-Token header). See AdminConsoleController.
                    .requestMatchers("/console", "/console/**").permitAll()
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

    /**
     * Empty on purpose. Logins go through AuthController + JWT, not Spring's
     * user store. Without this bean Spring Boot creates a default "user" and
     * prints its password in the production logs at every start.
     */
    @Bean
    fun userDetailsService(): UserDetailsService = InMemoryUserDetailsManager()
}