package com.taptaptips.server.config

import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.EnableCaching
import org.springframework.cache.caffeine.CaffeineCacheManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.TimeUnit

@Configuration
@EnableCaching
class CacheConfig {
    
    @Bean
    fun cacheManager(): CacheManager {
        val cacheManager = CaffeineCacheManager("rateLimitBuckets")
        cacheManager.setCaffeine(
            Caffeine.newBuilder()
                .expireAfterAccess(1, TimeUnit.HOURS)
                .maximumSize(10_000)  // Track up to 10k IPs/users
        )
        return cacheManager
    }
}