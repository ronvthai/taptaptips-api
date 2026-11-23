package com.taptaptips.server.security

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Long-lived, revocable sign-in for the mobile apps.
 *
 * Why this exists: the apps used to hold only a short-lived access JWT. Once
 * its `exp` passed (phone in a pocket overnight, app not opened for a while)
 * nothing could renew it — `/auth/refresh` needs a *valid* access token — so
 * the user was sent back to the login screen. That, plus a 30-minute idle
 * logout, was the "I have to log in constantly" complaint.
 *
 * Model:
 *   • Login / register hand out an opaque refresh token (256 random bits)
 *     alongside the access JWT. Only its SHA-256 hash is stored.
 *   • POST /auth/token swaps a refresh token for a new access JWT **and** a
 *     new refresh token (rotation). The old one is retired.
 *   • Sliding expiry: each rotation gives a fresh `refreshTtlDays` window, so
 *     anyone who opens the app at least once per window stays signed in.
 *   • Reuse detection: presenting an already-retired token (outside a short
 *     grace window) means it was copied — every token in that sign-in
 *     "family" is revoked and that device must log in again.
 *   • Grace window: if a phone sent the refresh, the server rotated, and the
 *     response was lost (tunnel, elevator, app killed), the phone retries
 *     with the old token. Within [graceSeconds] that's treated as a retry,
 *     not theft — otherwise flaky networks would cause the very logouts this
 *     fixes.
 *   • Logout revokes the device's family; password reset and account
 *     deletion revoke every family for the user.
 *
 * Table is created on startup (same pattern as ble_token / ReferralSchema),
 * so no migration needs to be run by hand.
 */
@Service
open class RefreshTokenService(
    private val jdbc: JdbcTemplate,
    @Value("\${jwt.refreshTtlDays:60}") private val refreshTtlDays: Long,
    @Value("\${jwt.refreshGraceSeconds:60}") private val graceSeconds: Long
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val random = SecureRandom()

    sealed class RotateResult {
        data class Ok(val userId: UUID, val refreshToken: String) : RotateResult()
        /** Unknown, expired, revoked or reused — the device must log in again. */
        object Invalid : RotateResult()
    }

    @EventListener(ApplicationReadyEvent::class)
    open fun ensureSchema() {
        try {
            jdbc.execute(
                """
                CREATE TABLE IF NOT EXISTS refresh_token (
                    id           uuid PRIMARY KEY,
                    user_id      uuid        NOT NULL,
                    family_id    uuid        NOT NULL,
                    token_hash   varchar(64) NOT NULL UNIQUE,
                    created_at   timestamptz NOT NULL,
                    expires_at   timestamptz NOT NULL,
                    revoked_at   timestamptz,
                    replaced_by  uuid,
                    device_info  varchar(200)
                )
                """.trimIndent()
            )
            jdbc.execute("CREATE INDEX IF NOT EXISTS ix_refresh_token_user ON refresh_token (user_id)")
            jdbc.execute("CREATE INDEX IF NOT EXISTS ix_refresh_token_family ON refresh_token (family_id)")
            log.info("✅ refresh_token schema ready (ttl=${refreshTtlDays}d, grace=${graceSeconds}s)")
        } catch (e: Exception) {
            log.error("❌ Could not create refresh_token table — apps will fall back to short sessions", e)
        }
    }

    /** New sign-in (login / register): starts a new family. */
    @Transactional
    open fun issue(userId: UUID, deviceInfo: String? = null): String =
        insert(userId, UUID.randomUUID(), deviceInfo).second

    /** Swap a refresh token for a new one. See class doc for the rules. */
    @Transactional
    open fun rotate(rawToken: String): RotateResult {
        if (rawToken.isBlank() || rawToken.length > 200) return RotateResult.Invalid
        val now = Instant.now()

        val row = jdbc.query(
            """
            SELECT id, user_id, family_id, expires_at, revoked_at, replaced_by, device_info
              FROM refresh_token WHERE token_hash = ? FOR UPDATE
            """.trimIndent(),
            { rs, _ ->
                Row(
                    id         = rs.getObject("id", UUID::class.java),
                    userId     = rs.getObject("user_id", UUID::class.java),
                    familyId   = rs.getObject("family_id", UUID::class.java),
                    expiresAt  = rs.getTimestamp("expires_at").toInstant(),
                    revokedAt  = rs.getTimestamp("revoked_at")?.toInstant(),
                    replacedBy = rs.getObject("replaced_by", UUID::class.java),
                    deviceInfo = rs.getString("device_info")
                )
            },
            hash(rawToken)
        ).firstOrNull() ?: return RotateResult.Invalid

        if (row.revokedAt != null) {
            val rotatedRecently = row.replacedBy != null &&
                Duration.between(row.revokedAt, now).seconds <= graceSeconds
            if (rotatedRecently && familyAlive(row.familyId)) {
                // Lost response + retry. Hand out another token in the same family.
                log.info("🔁 Refresh retry within grace for user ${row.userId}")
                val (_, raw) = insert(row.userId, row.familyId, row.deviceInfo)
                return RotateResult.Ok(row.userId, raw)
            }
            // A retired token came back long after rotation → treat as stolen.
            val n = jdbc.update(
                "UPDATE refresh_token SET revoked_at = ? WHERE family_id = ? AND revoked_at IS NULL",
                Timestamp.from(now), row.familyId
            )
            log.warn("🚨 Refresh token reuse for user ${row.userId} — revoked family ${row.familyId} ($n live)")
            return RotateResult.Invalid
        }

        if (!row.expiresAt.isAfter(now)) return RotateResult.Invalid

        val (newId, raw) = insert(row.userId, row.familyId, row.deviceInfo)
        jdbc.update(
            "UPDATE refresh_token SET revoked_at = ?, replaced_by = ? WHERE id = ?",
            Timestamp.from(now), newId, row.id
        )
        return RotateResult.Ok(row.userId, raw)
    }

    /** Logout on one device: revoke that device's whole family. */
    @Transactional
    open fun revoke(rawToken: String) {
        if (rawToken.isBlank() || rawToken.length > 200) return
        jdbc.update(
            """
            UPDATE refresh_token SET revoked_at = now()
             WHERE revoked_at IS NULL
               AND family_id = (SELECT family_id FROM refresh_token WHERE token_hash = ?)
            """.trimIndent(),
            hash(rawToken)
        )
    }

    /** Password reset, account deletion: sign out every device. */
    @Transactional
    open fun revokeAllForUser(userId: UUID): Int =
        jdbc.update(
            "UPDATE refresh_token SET revoked_at = now() WHERE user_id = ? AND revoked_at IS NULL",
            userId
        )

    /** Hard delete (account deletion). */
    @Transactional
    open fun deleteAllForUser(userId: UUID): Int =
        jdbc.update("DELETE FROM refresh_token WHERE user_id = ?", userId)

    /** Daily: drop rows nobody can use any more. */
    @Scheduled(cron = "0 17 4 * * *")
    open fun cleanup() {
        try {
            val n = jdbc.update(
                """
                DELETE FROM refresh_token
                 WHERE expires_at < now() - interval '1 day'
                    OR revoked_at < now() - interval '7 days'
                """.trimIndent()
            )
            if (n > 0) log.info("🧹 Removed $n old refresh tokens")
        } catch (e: Exception) {
            log.warn("Refresh token cleanup failed: ${e.message}")
        }
    }

    // ── internals ────────────────────────────────────────────────────────────

    private data class Row(
        val id: UUID, val userId: UUID, val familyId: UUID, val expiresAt: Instant,
        val revokedAt: Instant?, val replacedBy: UUID?, val deviceInfo: String?
    )

    private fun familyAlive(familyId: UUID): Boolean =
        (jdbc.queryForObject(
            "SELECT count(*) FROM refresh_token WHERE family_id = ? AND revoked_at IS NULL AND expires_at > now()",
            Long::class.java, familyId
        ) ?: 0L) > 0L

    private fun insert(userId: UUID, familyId: UUID, deviceInfo: String?): Pair<UUID, String> {
        val bytes = ByteArray(32).also(random::nextBytes)
        val raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val id = UUID.randomUUID()
        val now = Instant.now()
        jdbc.update(
            """
            INSERT INTO refresh_token (id, user_id, family_id, token_hash, created_at, expires_at, device_info)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            id, userId, familyId, hash(raw),
            Timestamp.from(now),
            Timestamp.from(now.plus(Duration.ofDays(refreshTtlDays))),
            deviceInfo?.take(200)
        )
        return id to raw
    }

    private fun hash(raw: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
