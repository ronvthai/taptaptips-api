package com.taptaptips.server.console

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Login + sessions for the read-only admin console at /console.
 *
 * - One shared password: the ADMIN_PASSWORD env var on Render. Blank or
 *   shorter than [MIN_PASSWORD_LENGTH] → the console refuses every login.
 * - Sessions are random 256-bit tokens kept in memory (only their SHA-256 is
 *   stored), valid for [SESSION_TTL] from login. A server restart or deploy
 *   signs everyone out — acceptable for an admin tool.
 * - Lockout: 5 wrong passwords from one IP within 15 min locks that IP for
 *   15 min; 20 wrong passwords from all IPs combined locks the console for
 *   everyone for 15 min (stops a distributed guessing attempt).
 *
 * The token travels in the X-Admin-Token header (not a cookie), so there is
 * nothing for another site to ride on — no CSRF exposure.
 */
@Component
class AdminConsoleAuth(
    @Value("\${ADMIN_PASSWORD:}") private val adminPassword: String,
    @Value("\${ratelimit.client-ip-header:CF-Connecting-IP}") private val clientIpHeader: String
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val HEADER = "X-Admin-Token"
        const val MIN_PASSWORD_LENGTH = 12
        val SESSION_TTL: Duration = Duration.ofHours(8)
        private val FAIL_WINDOW: Duration = Duration.ofMinutes(15)
        private val LOCK_FOR: Duration = Duration.ofMinutes(15)
        private const val MAX_FAILS_PER_IP = 5
        private const val MAX_FAILS_GLOBAL = 20
        private const val MAX_SESSIONS = 20
    }

    sealed class LoginResult {
        data class Ok(val token: String, val expiresAt: Instant) : LoginResult()
        data class Locked(val retryAfterSeconds: Long) : LoginResult()
        object WrongPassword : LoginResult()
        object Disabled : LoginResult()
    }

    private val random = SecureRandom()
    private val expectedDigest: ByteArray? =
        adminPassword.trim().takeIf { it.length >= MIN_PASSWORD_LENGTH }?.let { sha256(it) }

    /** sha256(token) hex → expiry */
    private val sessions = ConcurrentHashMap<String, Instant>()

    private class Failures(var count: Int, var windowStart: Instant, var lockedUntil: Instant?)
    private val failsByIp = ConcurrentHashMap<String, Failures>()
    private val globalFails = Failures(0, Instant.now(), null)

    init {
        if (expectedDigest == null) {
            log.warn(
                "🔒 Admin console disabled: ADMIN_PASSWORD is not set or shorter than " +
                    "$MIN_PASSWORD_LENGTH characters."
            )
        }
    }

    val enabled: Boolean get() = expectedDigest != null

    fun clientIp(req: HttpServletRequest): String =
        req.getHeader(clientIpHeader)?.trim()?.takeIf { it.isNotEmpty() } ?: req.remoteAddr ?: "unknown"

    @Synchronized
    fun login(password: String?, ip: String): LoginResult {
        val expected = expectedDigest ?: return LoginResult.Disabled
        val now = Instant.now()

        lockRemaining(globalFails, now)?.let { return LoginResult.Locked(it) }
        val ipFails = failsByIp.getOrPut(ip) { Failures(0, now, null) }
        lockRemaining(ipFails, now)?.let { return LoginResult.Locked(it) }

        val ok = password != null && MessageDigest.isEqual(sha256(password), expected)
        if (!ok) {
            recordFailure(ipFails, MAX_FAILS_PER_IP, now)
            recordFailure(globalFails, MAX_FAILS_GLOBAL, now)
            log.warn("🔒 Admin console: wrong password from $ip (${ipFails.count} recent)")
            if (failsByIp.size > 10_000) failsByIp.clear()
            return LoginResult.WrongPassword
        }

        failsByIp.remove(ip)
        purgeExpiredSessions(now)
        if (sessions.size >= MAX_SESSIONS) {
            // Drop the session closest to expiry to make room.
            sessions.entries.minByOrNull { it.value }?.let { sessions.remove(it.key) }
        }
        val bytes = ByteArray(32).also { random.nextBytes(it) }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val expiresAt = now.plus(SESSION_TTL)
        sessions[hex(sha256(token))] = expiresAt
        log.info("🔓 Admin console: login from $ip")
        return LoginResult.Ok(token, expiresAt)
    }

    /** True when the request carries a live session token. */
    fun isAuthorized(req: HttpServletRequest): Boolean {
        if (!enabled) return false
        val token = req.getHeader(HEADER)?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val key = hex(sha256(token))
        val exp = sessions[key] ?: return false
        if (exp.isBefore(Instant.now())) {
            sessions.remove(key)
            return false
        }
        return true
    }

    fun logout(req: HttpServletRequest) {
        req.getHeader(HEADER)?.trim()?.let { sessions.remove(hex(sha256(it))) }
    }

    // ── internals ────────────────────────────────────────────────────────────

    private fun lockRemaining(f: Failures, now: Instant): Long? {
        val until = f.lockedUntil ?: return null
        if (until.isAfter(now)) return Duration.between(now, until).seconds.coerceAtLeast(1)
        f.lockedUntil = null
        f.count = 0
        f.windowStart = now
        return null
    }

    private fun recordFailure(f: Failures, max: Int, now: Instant) {
        if (Duration.between(f.windowStart, now) > FAIL_WINDOW) {
            f.count = 0
            f.windowStart = now
        }
        f.count++
        if (f.count >= max) f.lockedUntil = now.plus(LOCK_FOR)
    }

    private fun purgeExpiredSessions(now: Instant) {
        sessions.entries.removeIf { it.value.isBefore(now) }
    }

    private fun sha256(s: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
}
