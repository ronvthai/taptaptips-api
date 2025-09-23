package com.taptaptips.server.config

import com.taptaptips.server.security.JwtAuthFilter
import com.taptaptips.server.security.JwtService
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
    private val jwtService: JwtService
) {

    @Bean
fun filterChain(http: HttpSecurity, jwt: JwtService): SecurityFilterChain {
    http
        .csrf { it.disable() } // REST: disable CSRF or you’ll get 403 on POST
        .cors { }              // keep your CORS bean
        .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        .authorizeHttpRequests { auth ->
            auth
                .requestMatchers(HttpMethod.POST, "/auth/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/health").permitAll()
                .requestMatchers(HttpMethod.POST, "/devices").authenticated()
                .requestMatchers(HttpMethod.POST, "/tips").authenticated() // <= only needs auth
                .anyRequest().authenticated()
        }
        .addFilterBefore(JwtAuthFilter(jwt), UsernamePasswordAuthenticationFilter::class.java)

    return http.build()
}


    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource =
        UrlBasedCorsConfigurationSource().apply {
            val c = CorsConfiguration()
            c.allowedOriginPatterns = listOf("*") // dev only; tighten for prod
            c.allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            c.allowedHeaders = listOf("*")
            registerCorsConfiguration("/**", c)
        }
}
