package com.taptaptips.server.service

import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import io.github.bucket4j.Refill
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import java.time.Duration

/**
 * Rate limiting service using Bucket4j token bucket algorithm
 * 
 * Token bucket works like this:
 * - Start with N tokens (capacity)
 * - Each request consumes 1 token
 * - Tokens refill at a fixed rate
 * - If no tokens available, request is rejected
 * 
 * This prevents burst attacks while allowing legitimate usage
 */
@Service
class RateLimiterService {
    
    /**
     * Rate limit for login attempts: 5 per 5 minutes per IP
     * 
     * This prevents brute force password attacks while allowing legitimate
     * users to retry a few times if they mistype their password.
     * 
     * After 5 failed attempts, users must wait 5 minutes.
     */
    @Cacheable(value = ["rateLimitBuckets"], key = "'login-' + #key")
    fun resolveLoginBucket(key: String): Bucket {
        return Bucket.builder()
            .addLimit(
                Bandwidth.classic(
                    5,  // capacity: 5 attempts
                    Refill.intervally(5, Duration.ofMinutes(5))  // refill 5 every 5 min
                )
            )
            .build()
    }
    
    /**
     * Rate limit for registration: 3 per 10 minutes per IP
     * 
     * Prevents automated account creation and spam.
     * Lower limit than login since registration is less frequent.
     */
    @Cacheable(value = ["rateLimitBuckets"], key = "'register-' + #key")
    fun resolveRegistrationBucket(key: String): Bucket {
        return Bucket.builder()
            .addLimit(
                Bandwidth.classic(
                    3,  // capacity: 3 attempts
                    Refill.intervally(3, Duration.ofMinutes(10))  // refill 3 every 10 min
                )
            )
            .build()
    }
    
    /**
     * Rate limit for password reset request: 3 per 15 minutes per IP
     * 
     * Prevents email bombing attacks where attacker spams reset emails.
     * 15-minute window gives legitimate users time to check their email.
     */
    @Cacheable(value = ["rateLimitBuckets"], key = "'reset-request-' + #key")
    fun resolvePasswordResetBucket(key: String): Bucket {
        return Bucket.builder()
            .addLimit(
                Bandwidth.classic(
                    3,  // capacity: 3 attempts
                    Refill.intervally(3, Duration.ofMinutes(15))  // refill 3 every 15 min
                )
            )
            .build()
    }
    
    /**
     * Rate limit for password reset completion: 5 per 10 minutes per IP
     * 
     * Prevents brute force attacks on reset tokens.
     * Slightly higher limit than reset requests since users might make
     * mistakes when entering new passwords.
     */
    @Cacheable(value = ["rateLimitBuckets"], key = "'reset-complete-' + #key")
    fun resolvePasswordResetCompletionBucket(key: String): Bucket {
        return Bucket.builder()
            .addLimit(
                Bandwidth.classic(
                    5,  // capacity: 5 attempts
                    Refill.intervally(5, Duration.ofMinutes(10))  // refill 5 every 10 min
                )
            )
            .build()
    }
    
    /**
     * Rate limit for token validation: 10 per 5 minutes per IP
     * 
     * Higher limit since this is a read-only operation and users might
     * reload the reset page multiple times.
     */
    @Cacheable(value = ["rateLimitBuckets"], key = "'validate-token-' + #key")
    fun resolveTokenValidationBucket(key: String): Bucket {
        return Bucket.builder()
            .addLimit(
                Bandwidth.classic(
                    10,  // capacity: 10 attempts
                    Refill.intervally(10, Duration.ofMinutes(5))  // refill 10 every 5 min
                )
            )
            .build()
    }
    
    /**
     * Rate limit for tip creation: 20 per minute per user
     * 
     * Prevents rapid-fire tip spam / potential money laundering.
     * 20 tips per minute should be more than enough for legitimate usage.
     */
    @Cacheable(value = ["rateLimitBuckets"], key = "'tips-' + #key")
    fun resolveTipCreationBucket(key: String): Bucket {
        return Bucket.builder()
            .addLimit(
                Bandwidth.classic(
                    20,  // capacity: 20 attempts
                    Refill.intervally(20, Duration.ofMinutes(1))  // refill 20 every 1 min
                )
            )
            .build()
    }
    
    /**
     * Rate limit for general API calls: 100 per minute per user
     * 
     * Prevents API abuse while allowing normal app usage.
     * Most users won't hit this limit in normal usage.
     */
    @Cacheable(value = ["rateLimitBuckets"], key = "'api-' + #key")
    fun resolveGeneralApiBucket(key: String): Bucket {
        return Bucket.builder()
            .addLimit(
                Bandwidth.classic(
                    100,  // capacity: 100 attempts
                    Refill.intervally(100, Duration.ofMinutes(1))  // refill 100 every 1 min
                )
            )
            .build()
    }
}