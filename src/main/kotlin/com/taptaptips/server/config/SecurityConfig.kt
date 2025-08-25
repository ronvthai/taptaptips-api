package com.taptaptips.server.config

import com.taptaptips.server.security.JwtAuthFilter
import com.taptaptips.server.security.JwtService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.web.SecurityFilterChain
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

@Configuration
@EnableWebSecurity
open class SecurityConfig(private val jwtService: JwtService) {

    @Bean
    open fun filterChain(http: HttpSecurity): SecurityFilterChain =
        http.csrf { it.disable() }
            .cors { }
            .authorizeHttpRequests {
                it.requestMatchers("/auth/**", "/actuator/health", "/ws/**").permitAll()
                it.anyRequest().authenticated()
            }
            .addFilterBefore(JwtAuthFilter(jwtService), UsernamePasswordAuthenticationFilter::class.java)
            .build()

    @Bean
    open fun corsConfigurationSource(): CorsConfigurationSource =
        UrlBasedCorsConfigurationSource().apply {
            val c = CorsConfiguration()
            c.allowedOriginPatterns = listOf("*") // dev only
            c.allowedMethods = listOf("GET","POST","PUT","DELETE","OPTIONS")
            c.allowedHeaders = listOf("*")
            registerCorsConfiguration("/**", c)
        }
}
