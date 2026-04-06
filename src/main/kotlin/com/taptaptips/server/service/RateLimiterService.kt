package com.taptaptips.server.service

import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import io.github.bucket4j.Refill
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import java.time.Duration

@Service
class RateLimiterService(
    private val cacheManager: CacheManager
) {

    /**
     * Login: 5 failed attempts per 5 minutes per IP.
     * Only failed logins consume tokens — successes reset the bucket.
     */
    @Cacheable(value = ["rateLimitBuckets"], key = "'login-' + #key")
    fun resolveLoginBucket(key: String): Bucket = Bucket.builder()
        .addLimit(Bandwidth.classic(5, Refill.intervally(5, Duration.ofMinutes(5))))
        .build()

    fun resetLoginBucket(ip: String) {
        cacheManager.getCache("rateLimitBuckets")?.evict("login-$ip")
    }

    /** Registration: 3 per 10 minutes per IP. */
    @Cacheable(value = ["rateLimitBuckets"], key = "'register-' + #key")
    fun resolveRegistrationBucket(key: String): Bucket = Bucket.builder()
        .addLimit(Bandwidth.classic(3, Refill.intervally(3, Duration.ofMinutes(10))))
        .build()

    /** Password-reset request: 3 per 15 minutes per IP (prevents email bombing). */
    @Cacheable(value = ["rateLimitBuckets"], key = "'reset-request-' + #key")
    fun resolvePasswordResetBucket(key: String): Bucket = Bucket.builder()
        .addLimit(Bandwidth.classic(3, Refill.intervally(3, Duration.ofMinutes(15))))
        .build()

    /** Password-reset completion: 5 per 10 minutes per IP. */
    @Cacheable(value = ["rateLimitBuckets"], key = "'reset-complete-' + #key")
    fun resolvePasswordResetCompletionBucket(key: String): Bucket = Bucket.builder()
        .addLimit(Bandwidth.classic(5, Refill.intervally(5, Duration.ofMinutes(10))))
        .build()

    /** Token validation: 10 per 5 minutes per IP. */
    @Cacheable(value = ["rateLimitBuckets"], key = "'validate-token-' + #key")
    fun resolveTokenValidationBucket(key: String): Bucket = Bucket.builder()
        .addLimit(Bandwidth.classic(10, Refill.intervally(10, Duration.ofMinutes(5))))
        .build()

    /** Tip creation: 20 per minute per user. */
    @Cacheable(value = ["rateLimitBuckets"], key = "'tips-' + #key")
    fun resolveTipCreationBucket(key: String): Bucket = Bucket.builder()
        .addLimit(Bandwidth.classic(20, Refill.intervally(20, Duration.ofMinutes(1))))
        .build()

    /**
     * BLE token issuance: 4 per 30 minutes per user.
     *
     * Tokens live 30 minutes. The client refreshes every 25 minutes, so one
     * token per window is the normal case. Allowing 4 gives room for app
     * restarts and session recovery without enabling token-map flooding.
     */
    @Cacheable(value = ["rateLimitBuckets"], key = "'ble-token-' + #key")
    fun resolveBleBucket(key: String): Bucket = Bucket.builder()
        .addLimit(Bandwidth.classic(4, Refill.intervally(4, Duration.ofMinutes(30))))
        .build()

    /** General authenticated API: 100 per minute per user. */
    @Cacheable(value = ["rateLimitBuckets"], key = "'api-' + #key")
    fun resolveGeneralApiBucket(key: String): Bucket = Bucket.builder()
        .addLimit(Bandwidth.classic(100, Refill.intervally(100, Duration.ofMinutes(1))))
        .build()
}
