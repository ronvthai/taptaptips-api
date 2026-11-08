package com.taptaptips.server.console

import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Read-only reporting queries for the admin console. Plain SQL (no entity
 * changes), every query is a SELECT, and every user-supplied value is a bind
 * parameter. Sort columns come from a fixed whitelist.
 *
 * "Completed" tips = SUCCEEDED + HELD (HELD is real money charged and waiting
 * for the receiver's bank). Amounts are what the sender paid, in cents.
 */
@Service
class AdminReportService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val tracker: LastActiveTracker,
    @Value("\${STRIPE_API_KEY:}") stripeKey: String,
    @Value("\${tips.guest.timezone:America/Los_Angeles}") zone: String
) {
    val stripeTestMode: Boolean = stripeKey.trim().startsWith("sk_test") || stripeKey.trim().startsWith("rk_test")
    private val zoneId: ZoneId = runCatching { ZoneId.of(zone) }.getOrDefault(ZoneId.of("America/Los_Angeles"))

    companion object {
        private const val COMPLETED = "('SUCCEEDED','HELD')"
        const val MAX_PAGE_SIZE = 200
        const val MAX_EXPORT_ROWS = 50_000

        /** Public sort key → SQL expression (whitelist; never user text in SQL). */
        private val SORTS = mapOf(
            "joined" to "created_at",
            "name" to "lower(display_name)",
            "email" to "lower(email)",
            "lastActive" to "last_active",
            "sentCount" to "sent_count",
            "sentAmount" to "sent_cents",
            "recvCount" to "recv_count",
            "recvAmount" to "recv_cents",
            "bank" to "bank_rank",
            "card" to "has_card"
        )
    }

    data class UserFilter(
        val q: String? = null,
        val bank: String? = null,        // linked | started | none
        val card: String? = null,        // yes | no
        val receiver: String? = null,    // yes | no
        val suspended: String? = null,   // yes | no
        val platform: String? = null,    // ios | android | unknown
        val active: String? = null,      // 7 | 30 | inactive30 | never
        val joinedFrom: LocalDate? = null,
        val joinedTo: LocalDate? = null,
        val sort: String = "joined",
        val dir: String = "desc"
    )

    /** One row per user with everything the table can sort/filter on. */
    private fun baseCte(): String {
        val lastActiveCol = if (tracker.ready) "u.last_active_at" else "NULL::timestamptz"
        return """
        WITH s AS (
            SELECT sender_id AS uid, count(*) AS n, COALESCE(sum(amount), 0) AS amt, max(created_at) AS last_at
            FROM tip WHERE status IN $COMPLETED AND sender_id IS NOT NULL GROUP BY sender_id
        ), r AS (
            SELECT receiver_id AS uid, count(*) AS n, COALESCE(sum(amount), 0) AS amt, max(created_at) AS last_at
            FROM tip WHERE status IN $COMPLETED AND receiver_id IS NOT NULL GROUP BY receiver_id
        ), d AS (
            SELECT user_id AS uid, max(last_seen) AS last_at,
                   bool_or(lower(platform) = 'ios') AS ios, bool_or(lower(platform) = 'android') AS android
            FROM device GROUP BY user_id
        ), f AS (
            SELECT user_id AS uid, max(updated_at) AS last_at,
                   bool_or(lower(platform) = 'ios') AS ios, bool_or(lower(platform) = 'android') AS android
            FROM fcm_token GROUP BY user_id
        ), base AS (
            SELECT u.id, u.display_name, u.email::text AS email, u.created_at,
                   u.wants_to_receive_tips AS receiver, u.stripe_onboarded, u.stripe_account_id,
                   (u.default_payment_method_id IS NOT NULL) AS has_card,
                   COALESCE(u.suspended, false) AS suspended,
                   CASE WHEN u.stripe_onboarded THEN 2 WHEN u.stripe_account_id IS NOT NULL THEN 1 ELSE 0 END AS bank_rank,
                   COALESCE(s.n, 0) AS sent_count, (COALESCE(s.amt, 0) * 100)::bigint AS sent_cents,
                   COALESCE(r.n, 0) AS recv_count, (COALESCE(r.amt, 0) * 100)::bigint AS recv_cents,
                   GREATEST($lastActiveCol, d.last_at, f.last_at, s.last_at, r.last_at) AS last_active,
                   (COALESCE(d.ios, false) OR COALESCE(f.ios, false)) AS has_ios,
                   (COALESCE(d.android, false) OR COALESCE(f.android, false)) AS has_android
            FROM app_user u
            LEFT JOIN s ON s.uid = u.id
            LEFT JOIN r ON r.uid = u.id
            LEFT JOIN d ON d.uid = u.id
            LEFT JOIN f ON f.uid = u.id
        )
        """.trimIndent()
    }

    // ── Overview ─────────────────────────────────────────────────────────────

    fun overview(): Map<String, Any?> {
        val now = Instant.now()
        val todayStart = LocalDate.now(zoneId).atStartOfDay(zoneId).toInstant()
        val p = MapSqlParameterSource()
            .addValue("today", Timestamp.from(todayStart))
            .addValue("d7", Timestamp.from(now.minus(7, ChronoUnit.DAYS)))
            .addValue("d30", Timestamp.from(now.minus(30, ChronoUnit.DAYS)))

        val users = jdbc.queryForMap(
            baseCte() + """
            SELECT count(*) AS total,
                   count(*) FILTER (WHERE created_at >= :today) AS new_today,
                   count(*) FILTER (WHERE created_at >= :d7) AS new_7d,
                   count(*) FILTER (WHERE created_at >= :d30) AS new_30d,
                   count(*) FILTER (WHERE last_active >= :d7) AS active_7d,
                   count(*) FILTER (WHERE last_active >= :d30) AS active_30d,
                   count(*) FILTER (WHERE receiver) AS receivers,
                   count(*) FILTER (WHERE bank_rank = 2) AS bank_linked,
                   count(*) FILTER (WHERE bank_rank = 1) AS bank_started,
                   count(*) FILTER (WHERE has_card) AS card_on_file,
                   count(*) FILTER (WHERE suspended) AS suspended,
                   count(*) FILTER (WHERE has_ios) AS ios,
                   count(*) FILTER (WHERE has_android) AS android,
                   count(*) FILTER (WHERE NOT has_ios AND NOT has_android) AS platform_unknown,
                   count(*) FILTER (WHERE sent_count > 0) AS ever_sent,
                   count(*) FILTER (WHERE recv_count > 0) AS ever_received
            FROM base
            """.trimIndent(), p
        ).mapValues { (it.value as? Number)?.toLong() ?: 0L }

        val tips = jdbc.queryForMap(
            """
            SELECT count(*) AS count_all, (COALESCE(sum(amount), 0) * 100)::bigint AS cents_all,
                   count(*) FILTER (WHERE created_at >= :d30) AS count_30d,
                   (COALESCE(sum(amount) FILTER (WHERE created_at >= :d30), 0) * 100)::bigint AS cents_30d,
                   count(*) FILTER (WHERE created_at >= :today) AS count_today,
                   (COALESCE(sum(amount) FILTER (WHERE created_at >= :today), 0) * 100)::bigint AS cents_today
            FROM tip WHERE status IN $COMPLETED
            """.trimIndent(), p
        ).mapValues { (it.value as? Number)?.toLong() ?: 0L }

        val held = runCatching {
            jdbc.queryForMap(
                "SELECT count(*) AS count, COALESCE(sum(net_cents), 0) AS net_cents, " +
                    "count(DISTINCT receiver_id) AS receivers FROM held_tip WHERE status = 'HELD'",
                MapSqlParameterSource()
            ).mapValues { (it.value as? Number)?.toLong() ?: 0L }
        }.getOrNull()

        val guest = runCatching {
            jdbc.queryForMap(
                "SELECT count(*) AS count, COALESCE(sum(amount_cents), 0) AS cents FROM guest_tip WHERE status = 'SUCCEEDED'",
                MapSqlParameterSource()
            ).mapValues { (it.value as? Number)?.toLong() ?: 0L }
        }.getOrNull()

        val referrals = runCatching {
            jdbc.query("SELECT status, count(*) AS n FROM referral GROUP BY status", MapSqlParameterSource()) { rs, _ ->
                rs.getString("status") to rs.getLong("n")
            }.toMap()
        }.getOrNull()

        val topReceivers = top("receiver_id", p)
        val topSenders = top("sender_id", p)

        return mapOf(
            "generatedAt" to now.toString(),
            "timezone" to zoneId.id,
            "stripeTestMode" to stripeTestMode,
            "lastActiveTracking" to tracker.ready,
            "users" to users,
            "tips" to tips,
            "held" to held,
            "guestTips" to guest,
            "referrals" to referrals,
            "signupsByDay" to signupsByDay(90),
            "topReceivers30d" to topReceivers,
            "topSenders30d" to topSenders
        )
    }

    private fun top(col: String, p: MapSqlParameterSource): List<Map<String, Any?>> {
        require(col == "receiver_id" || col == "sender_id")
        return jdbc.query(
            """
            SELECT t.$col AS uid, u.display_name, count(*) AS n, (sum(t.amount) * 100)::bigint AS cents
            FROM tip t JOIN app_user u ON u.id = t.$col
            WHERE t.status IN $COMPLETED AND t.created_at >= :d30
            GROUP BY t.$col, u.display_name ORDER BY cents DESC LIMIT 10
            """.trimIndent(), p
        ) { rs, _ ->
            mapOf(
                "id" to rs.getString("uid"),
                "displayName" to rs.getString("display_name"),
                "count" to rs.getLong("n"),
                "cents" to rs.getLong("cents")
            )
        }
    }

    private fun signupsByDay(days: Long): List<Map<String, Any>> {
        val today = LocalDate.now(zoneId)
        val first = today.minusDays(days - 1)
        val p = MapSqlParameterSource()
            .addValue("since", Timestamp.from(first.atStartOfDay(zoneId).toInstant()))
            .addValue("tz", zoneId.id)
        val counts = jdbc.query(
            "SELECT (created_at AT TIME ZONE :tz)::date AS d, count(*) AS n FROM app_user " +
                "WHERE created_at >= :since GROUP BY 1", p
        ) { rs, _ -> rs.getObject("d", LocalDate::class.java) to rs.getLong("n") }.toMap()
        var running = jdbc.queryForObject(
            "SELECT count(*) FROM app_user WHERE created_at < :since", p, Long::class.java
        ) ?: 0L
        return (0 until days).map { i ->
            val d = first.plusDays(i)
            val n = counts[d] ?: 0L
            running += n
            mapOf("day" to d.toString(), "count" to n, "total" to running)
        }
    }

    // ── Users table ──────────────────────────────────────────────────────────

    data class Page(val total: Long, val rows: List<Map<String, Any?>>)

    fun users(f: UserFilter, page: Int, size: Int): Page {
        val limit = size.coerceIn(1, MAX_PAGE_SIZE)
        val offset = (page.coerceAtLeast(0).toLong() * limit)
        return queryUsers(f, limit, offset)
    }

    fun usersForExport(f: UserFilter): List<Map<String, Any?>> =
        queryUsers(f, MAX_EXPORT_ROWS, 0).rows

    private fun queryUsers(f: UserFilter, limit: Int, offset: Long): Page {
        val p = MapSqlParameterSource()
        val where = mutableListOf<String>()

        f.q?.trim()?.takeIf { it.isNotEmpty() }?.let { q ->
            val like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            p.addValue("qlike", like).addValue("qraw", q.lowercase())
            where += "(display_name ILIKE :qlike OR email ILIKE :qlike OR id::text = :qraw)"
        }
        when (f.bank) {
            "linked" -> where += "bank_rank = 2"
            "started" -> where += "bank_rank = 1"
            "none" -> where += "bank_rank = 0"
        }
        when (f.card) { "yes" -> where += "has_card"; "no" -> where += "NOT has_card" }
        when (f.receiver) { "yes" -> where += "receiver"; "no" -> where += "NOT receiver" }
        when (f.suspended) { "yes" -> where += "suspended"; "no" -> where += "NOT suspended" }
        when (f.platform) {
            "ios" -> where += "has_ios"
            "android" -> where += "has_android"
            "unknown" -> where += "(NOT has_ios AND NOT has_android)"
        }
        val now = Instant.now()
        when (f.active) {
            "7" -> { where += "last_active >= :act"; p.addValue("act", Timestamp.from(now.minus(7, ChronoUnit.DAYS))) }
            "30" -> { where += "last_active >= :act"; p.addValue("act", Timestamp.from(now.minus(30, ChronoUnit.DAYS))) }
            "inactive30" -> { where += "(last_active IS NULL OR last_active < :act)"; p.addValue("act", Timestamp.from(now.minus(30, ChronoUnit.DAYS))) }
            "never" -> where += "last_active IS NULL"
        }
        f.joinedFrom?.let {
            where += "created_at >= :jfrom"
            p.addValue("jfrom", Timestamp.from(it.atStartOfDay(zoneId).toInstant()))
        }
        f.joinedTo?.let {
            where += "created_at < :jto"
            p.addValue("jto", Timestamp.from(it.plusDays(1).atStartOfDay(zoneId).toInstant()))
        }

        val sortExpr = SORTS[f.sort] ?: "created_at"
        val dir = if (f.dir.equals("asc", ignoreCase = true)) "ASC" else "DESC"
        p.addValue("limit", limit).addValue("offset", offset)

        val sql = baseCte() + """
            SELECT *, count(*) OVER () AS total_rows FROM base
            ${if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ")}
            ORDER BY $sortExpr $dir NULLS LAST, id
            LIMIT :limit OFFSET :offset
        """.trimIndent()

        var total = 0L
        val rows = jdbc.query(sql, p) { rs, _ ->
            total = rs.getLong("total_rows")
            userRow(rs)
        }
        if (rows.isEmpty() && offset > 0) {
            // Past the last page — still report the real total.
            total = jdbc.queryForObject(
                baseCte() + " SELECT count(*) FROM base " +
                    (if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ")),
                p, Long::class.java
            ) ?: 0L
        }
        return Page(total, rows)
    }

    private fun userRow(rs: ResultSet): Map<String, Any?> {
        val bankRank = rs.getInt("bank_rank")
        return linkedMapOf(
            "id" to rs.getString("id"),
            "displayName" to rs.getString("display_name"),
            "email" to rs.getString("email"),
            "joined" to ts(rs, "created_at"),
            "lastActive" to ts(rs, "last_active"),
            "bank" to when (bankRank) { 2 -> "linked"; 1 -> "started"; else -> "none" },
            "card" to rs.getBoolean("has_card"),
            "receiver" to rs.getBoolean("receiver"),
            "suspended" to rs.getBoolean("suspended"),
            "platforms" to listOfNotNull(
                "ios".takeIf { rs.getBoolean("has_ios") },
                "android".takeIf { rs.getBoolean("has_android") }
            ),
            "sentCount" to rs.getLong("sent_count"),
            "sentCents" to rs.getLong("sent_cents"),
            "recvCount" to rs.getLong("recv_count"),
            "recvCents" to rs.getLong("recv_cents")
        )
    }

    // ── User detail ──────────────────────────────────────────────────────────

    fun user(id: UUID): Map<String, Any?>? {
        val p = MapSqlParameterSource("id", id)
        val summary = jdbc.query(baseCte() + " SELECT * FROM base WHERE id = :id", p) { rs, _ -> userRow(rs) }
            .firstOrNull() ?: return null

        val lastActiveRaw = if (tracker.ready) "last_active_at" else "NULL::timestamptz AS last_active_at"
        val profile = jdbc.query(
            """
            SELECT updated_at, $lastActiveRaw, stripe_account_id, stripe_customer_id, bank_name, bank_last4,
                   suspension_reason, suspended_at, stripe_last_checked, (profile_picture IS NOT NULL) AS has_avatar
            FROM app_user WHERE id = :id
            """.trimIndent(), p
        ) { rs, _ ->
            mapOf(
                "updatedAt" to ts(rs, "updated_at"),
                "lastAppUse" to ts(rs, "last_active_at"),
                "stripeAccountId" to rs.getString("stripe_account_id"),
                "stripeCustomerId" to rs.getString("stripe_customer_id"),
                "bankName" to rs.getString("bank_name"),
                "bankLast4" to rs.getString("bank_last4"),
                "suspensionReason" to rs.getString("suspension_reason"),
                "suspendedAt" to ts(rs, "suspended_at"),
                "stripeLastChecked" to ts(rs, "stripe_last_checked"),
                "hasAvatar" to rs.getBoolean("has_avatar")
            )
        }.first()

        fun tipList(mine: String, other: String) = jdbc.query(
            """
            SELECT t.id, t.status, t.created_at, (t.amount * 100)::bigint AS cents,
                   (t.net_amount * 100)::bigint AS net_cents, t.$other AS other_id, u.display_name AS other_name
            FROM tip t LEFT JOIN app_user u ON u.id = t.$other
            WHERE t.$mine = :id ORDER BY t.created_at DESC LIMIT 100
            """.trimIndent(), p
        ) { rs, _ ->
            mapOf(
                "id" to rs.getString("id"),
                "status" to rs.getString("status"),
                "at" to ts(rs, "created_at"),
                "cents" to rs.getLong("cents"),
                "netCents" to rs.getObject("net_cents")?.let { (it as Number).toLong() },
                "otherId" to rs.getString("other_id"),
                "otherName" to rs.getString("other_name")
            )
        }

        fun statusBreakdown(col: String) = jdbc.query(
            "SELECT status, count(*) AS n, (COALESCE(sum(amount), 0) * 100)::bigint AS cents " +
                "FROM tip WHERE $col = :id GROUP BY status ORDER BY status", p
        ) { rs, _ -> mapOf("status" to rs.getString("status"), "count" to rs.getLong("n"), "cents" to rs.getLong("cents")) }

        val held = runCatching {
            jdbc.query(
                "SELECT status, gross_cents, net_cents, created_at, expires_at, released_at, refunded_at " +
                    "FROM held_tip WHERE receiver_id = :id ORDER BY created_at DESC LIMIT 50", p
            ) { rs, _ ->
                mapOf(
                    "status" to rs.getString("status"),
                    "grossCents" to rs.getLong("gross_cents"),
                    "netCents" to rs.getLong("net_cents"),
                    "at" to ts(rs, "created_at"),
                    "expiresAt" to ts(rs, "expires_at"),
                    "releasedAt" to ts(rs, "released_at"),
                    "refundedAt" to ts(rs, "refunded_at")
                )
            }
        }.getOrDefault(emptyList())

        val referralCode = runCatching {
            jdbc.query("SELECT code FROM referral_code WHERE user_id = :id LIMIT 1", p) { rs, _ -> rs.getString("code") }
                .firstOrNull()
        }.getOrNull()

        val referredBy = runCatching {
            jdbc.query(
                "SELECT r.status, r.referrer_id, u.display_name FROM referral r " +
                    "LEFT JOIN app_user u ON u.id = r.referrer_id WHERE r.referred_user_id = :id LIMIT 1", p
            ) { rs, _ ->
                mapOf("status" to rs.getString("status"), "id" to rs.getString("referrer_id"), "displayName" to rs.getString("display_name"))
            }.firstOrNull()
        }.getOrNull()

        val referralsMade = runCatching {
            jdbc.query(
                "SELECT r.status, r.reward_cents, r.created_at, r.referred_user_id, u.display_name FROM referral r " +
                    "LEFT JOIN app_user u ON u.id = r.referred_user_id WHERE r.referrer_id = :id " +
                    "ORDER BY r.created_at DESC LIMIT 100", p
            ) { rs, _ ->
                mapOf(
                    "status" to rs.getString("status"),
                    "rewardCents" to rs.getLong("reward_cents"),
                    "at" to ts(rs, "created_at"),
                    "id" to rs.getString("referred_user_id"),
                    "displayName" to rs.getString("display_name")
                )
            }
        }.getOrDefault(emptyList())

        val devices = runCatching {
            jdbc.query(
                "SELECT platform, device_name, last_seen, created_at, is_active FROM device " +
                    "WHERE user_id = :id ORDER BY last_seen DESC NULLS LAST", p
            ) { rs, _ ->
                mapOf(
                    "platform" to rs.getString("platform"),
                    "name" to rs.getString("device_name"),
                    "lastSeen" to ts(rs, "last_seen"),
                    "added" to ts(rs, "created_at"),
                    "active" to rs.getObject("is_active")
                )
            }
        }.getOrDefault(emptyList())

        val push = runCatching {
            jdbc.query(
                "SELECT platform, is_active, updated_at, device_info FROM fcm_token " +
                    "WHERE user_id = :id ORDER BY updated_at DESC", p
            ) { rs, _ ->
                mapOf(
                    "platform" to rs.getString("platform"),
                    "active" to rs.getBoolean("is_active"),
                    "updated" to ts(rs, "updated_at"),
                    "device" to rs.getString("device_info")
                )
            }
        }.getOrDefault(emptyList())

        return mapOf(
            "summary" to summary,
            "profile" to profile,
            "stripeTestMode" to stripeTestMode,
            "sentByStatus" to statusBreakdown("sender_id"),
            "receivedByStatus" to statusBreakdown("receiver_id"),
            "tipsSent" to tipList("sender_id", "receiver_id"),
            "tipsReceived" to tipList("receiver_id", "sender_id"),
            "heldTips" to held,
            "referralCode" to referralCode,
            "referredBy" to referredBy,
            "referralsMade" to referralsMade,
            "devices" to devices,
            "pushTokens" to push
        )
    }

    fun avatar(id: UUID): ByteArray? =
        jdbc.query("SELECT profile_picture FROM app_user WHERE id = :id", MapSqlParameterSource("id", id)) { rs, _ ->
            rs.getBytes("profile_picture")
        }.firstOrNull()

    private fun ts(rs: ResultSet, col: String): String? = rs.getTimestamp(col)?.toInstant()?.toString()
}
