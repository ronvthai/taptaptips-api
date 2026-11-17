package com.taptaptips.server.web

import com.taptaptips.server.console.AdminConsoleAuth
import com.taptaptips.server.security.ClientIp
import com.taptaptips.server.service.FraudGuardService
import com.taptaptips.server.service.RuntimeSettings
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.CacheControl
import java.net.URI
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Admin API for runtime settings and the ban list. The screens for it are the
 * Settings and Fraud tabs of the admin dashboard at /console; /admin just
 * redirects there.
 *
 * Every call needs ONE of:
 *  - X-Admin-Token: a session from signing in to /console (AdminConsoleAuth), or
 *  - X-Admin-Key:   ADMIN_PASSWORD itself (for scripts / curl).
 *
 * Either way these calls CHANGE things, so ADMIN_PASSWORD must be at least
 * 16 characters or they all refuse (the read-only reports only need 12).
 * 5 wrong X-Admin-Key passwords from one address lock it out for 15 minutes.
 * Every change is logged with who/when.
 *
 * All /admin paths must be permitAll in SecurityConfig — this controller does
 * its own checks instead of the app's JWT login.
 */
@RestController
class AdminController(
    private val settings: RuntimeSettings,
    private val clientIpResolver: ClientIp,
    private val fraudGuard: FraudGuardService,
    private val consoleAuth: AdminConsoleAuth,
    @Value("\${admin.password:}") private val adminPassword: String
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val failures = ConcurrentHashMap<String, ArrayDeque<Instant>>()

    companion object {
        private const val MIN_PASSWORD_LENGTH = 16
        private const val MAX_FAILURES = 5
        private val LOCKOUT: Duration = Duration.ofMinutes(15)
    }

    data class SetRequest(val key: String = "", val value: String = "")
    data class KeyRequest(val key: String = "")
    data class BanRequest(val userId: String = "", val reason: String = "", val extraIps: List<String> = emptyList())
    data class RemoveBanRequest(val type: String = "", val value: String = "")
    data class UnbanRequest(val userId: String = "")

    // ── Page ─────────────────────────────────────────────────────────────────

    /** The old settings page moved into the dashboard. Keeps bookmarks working. */
    @GetMapping("/admin")
    fun page(): ResponseEntity<Void> =
        ResponseEntity.status(HttpStatus.FOUND)
            .location(URI.create("/console#/settings"))
            .cacheControl(CacheControl.noStore())
            .build()

    // ── API ──────────────────────────────────────────────────────────────────

    @PostMapping("/admin/api/login")
    fun login(req: HttpServletRequest): ResponseEntity<Any> =
        guard(req) { ResponseEntity.ok(mapOf("ok" to true)) }

    @GetMapping("/admin/api/settings")
    fun list(req: HttpServletRequest): ResponseEntity<Any> =
        guard(req) { ResponseEntity.ok(settings.list()) }

    @PostMapping("/admin/api/settings")
    fun set(req: HttpServletRequest, @RequestBody body: SetRequest): ResponseEntity<Any> =
        guard(req) {
            try {
                settings.set(body.key, body.value, "admin@" + clientIp(req))
                ResponseEntity.ok(settings.list())
            } catch (e: IllegalArgumentException) {
                ResponseEntity.badRequest().body(mapOf("error" to (e.message ?: "Not allowed.")))
            }
        }

    @PostMapping("/admin/api/settings/reset")
    fun reset(req: HttpServletRequest, @RequestBody body: KeyRequest): ResponseEntity<Any> =
        guard(req) {
            try {
                settings.reset(body.key, "admin@" + clientIp(req))
                ResponseEntity.ok(settings.list())
            } catch (e: IllegalArgumentException) {
                ResponseEntity.badRequest().body(mapOf("error" to (e.message ?: "Not allowed.")))
            }
        }

    // ── Ban list ─────────────────────────────────────────────────────────────

    @GetMapping("/admin/api/bans")
    fun bans(req: HttpServletRequest): ResponseEntity<Any> =
        guard(req) { ResponseEntity.ok(banList()) }

    /** Suspend a user and put their email, IPs and payout bank on the ban list. */
    @PostMapping("/admin/api/ban")
    fun ban(req: HttpServletRequest, @RequestBody body: BanRequest): ResponseEntity<Any> =
        guard(req) {
            val id = runCatching { UUID.fromString(body.userId.trim()) }.getOrNull()
                ?: return@guard ResponseEntity.badRequest().body(mapOf("error" to "Enter a valid user ID."))
            if (body.reason.isBlank())
                return@guard ResponseEntity.badRequest().body(mapOf("error" to "Enter a reason."))
            try {
                val result = fraudGuard.ban(id, body.reason.trim(), body.extraIps, "admin@" + clientIp(req))
                ResponseEntity.ok(mapOf("result" to result, "bans" to banList()))
            } catch (e: IllegalArgumentException) {
                ResponseEntity.badRequest().body(mapOf("error" to (e.message ?: "Not found.")))
            }
        }

    @PostMapping("/admin/api/bans/remove")
    fun removeBan(req: HttpServletRequest, @RequestBody body: RemoveBanRequest): ResponseEntity<Any> =
        guard(req) {
            fraudGuard.unbanIdentifier(body.type, body.value, "admin@" + clientIp(req))
            ResponseEntity.ok(banList())
        }

    /** Undo a ban: unsuspend, drop the user's ban-list entries, resume Stripe payouts. */
    @PostMapping("/admin/api/unban")
    fun unban(req: HttpServletRequest, @RequestBody body: UnbanRequest): ResponseEntity<Any> =
        guard(req) {
            val id = runCatching { UUID.fromString(body.userId.trim()) }.getOrNull()
                ?: return@guard ResponseEntity.badRequest().body(mapOf("error" to "Enter a valid user ID."))
            try {
                val result = fraudGuard.unban(id, "admin@" + clientIp(req))
                ResponseEntity.ok(mapOf("result" to result, "bans" to banList()))
            } catch (e: IllegalArgumentException) {
                ResponseEntity.badRequest().body(mapOf("error" to (e.message ?: "Not found.")))
            }
        }

    private fun banList(): List<Map<String, Any?>> =
        fraudGuard.list().map {
            mapOf(
                "type" to it.type, "value" to it.value, "reason" to it.reason,
                "sourceUserId" to it.sourceUserId?.toString(), "createdAt" to it.createdAt.toString()
            )
        }

    // ── Password check ───────────────────────────────────────────────────────

    private fun guard(req: HttpServletRequest, action: () -> ResponseEntity<Any>): ResponseEntity<Any> {
        val expected = adminPassword.trim()
        if (expected.length < MIN_PASSWORD_LENGTH) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(mapOf("error" to
                "Changes are locked. Set ADMIN_PASSWORD in Render to at least $MIN_PASSWORD_LENGTH characters."))
        }
        // Signed in to the dashboard (/console) → allowed. An expired dashboard
        // session is just "sign in again", not a wrong password, so it doesn't
        // count toward the lockout.
        if (consoleAuth.isAuthorized(req)) return action()
        if (!req.getHeader(AdminConsoleAuth.HEADER).isNullOrBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(mapOf("error" to "Not signed in"))
        }

        val ip = clientIp(req)
        if (lockedOut(ip)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(mapOf("error" to "Too many wrong passwords. Try again in 15 minutes."))
        }
        val given = req.getHeader("X-Admin-Key") ?: ""
        if (!sameSecret(given, expected)) {
            recordFailure(ip)
            log.warn("🔒 Admin: wrong password from $ip")
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(mapOf("error" to "Wrong password."))
        }
        failures.remove(ip)
        return action()
    }

    /** Constant-time compare (hashing first so different lengths don't leak). */
    private fun sameSecret(a: String, b: String): Boolean {
        val sha = MessageDigest.getInstance("SHA-256")
        return MessageDigest.isEqual(sha.digest(a.toByteArray()), sha.digest(b.toByteArray()))
    }

    private fun lockedOut(ip: String): Boolean {
        val q = failures[ip] ?: return false
        synchronized(q) {
            val cutoff = Instant.now().minus(LOCKOUT)
            while (q.isNotEmpty() && q.peekFirst().isBefore(cutoff)) q.pollFirst()
            return q.size >= MAX_FAILURES
        }
    }

    private fun recordFailure(ip: String) {
        val q = failures.computeIfAbsent(ip) { ArrayDeque() }
        synchronized(q) { q.addLast(Instant.now()) }
    }

    private fun clientIp(req: HttpServletRequest): String = clientIpResolver.of(req)
}
