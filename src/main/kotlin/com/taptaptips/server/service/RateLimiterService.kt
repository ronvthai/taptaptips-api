package com.taptaptips.server.service

import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import io.github.bucket4j.Refill
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import java.time.Duration

@Service
class RateLimiterService {
    
    /**
     * Rate limit for login attempts: 5 per 15 minutes per IP
     * Prevents brute force password attacks
     */
    @Cacheable(value = ["rateLimitBuckets"], key = "'login-' + #key")
    fun resolveLoginBucket(key: String): Bucket {
        return Bucket.builder()
            .addLimit(
                Bandwidth.classic(
                    5,  // capacity: 5 attempts
                    Refill.intervally(5, Duration.ofMinutes(15))  // refill 5 every 15 min
                )
            )
            .build()
    }
    
    /**
     * Rate limit for password reset: 3 per hour per email
     * Prevents email bombing attacks
     */
    @Cacheable(value = ["rateLimitBuckets"], key = "'reset-' + #key")
    fun resolvePasswordResetBucket(key: String): Bucket {
        return Bucket.builder()
            .addLimit(
                Bandwidth.classic(
                    3,
                    Refill.intervally(3, Duration.ofHours(1))
                )
            )
            .build()
    }
    
    /**
     * Rate limit for tip creation: 20 per minute per user
     * Prevents rapid-fire tip spam / money laundering
     */
    @Cacheable(value = ["rateLimitBuckets"], key = "'tips-' + #key")
    fun resolveTipCreationBucket(key: String): Bucket {
        return Bucket.builder()
            .addLimit(
                Bandwidth.classic(
                    20,
                    Refill.intervally(20, Duration.ofMinutes(1))
                )
            )
            .build()
    }
    
    /**
     * Rate limit for general API calls: 100 per minute per user
     * Prevents API abuse
     */
    @Cacheable(value = ["rateLimitBuckets"], key = "'api-' + #key")
    fun resolveGeneralApiBucket(key: String): Bucket {
        return Bucket.builder()
            .addLimit(
                Bandwidth.classic(
                    100,
                    Refill.intervally(100, Duration.ofMinutes(1))
                )
            )
            .build()
    }
}