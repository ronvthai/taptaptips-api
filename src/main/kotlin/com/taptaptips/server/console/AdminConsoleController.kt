package com.taptaptips.server.console

import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.io.ClassPathResource
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Admin dashboard: reports and users (read-only, here) plus the Settings and
 * Fraud tabs, whose page lives in the same console files but whose API is
 * AdminController (/admin/api/…, which accepts this console's session).
 *
 *   GET  /console                    the web page (login screen + reports)
 *   GET  /console/console.js|.css    its script and styles
 *   POST /console/api/login          { "password": "…" } → { token, expiresAt }
 *   POST /console/api/logout
 *   GET  /console/api/overview       headline numbers + signups chart
 *   GET  /console/api/users          search / filter / sort / page
 *   GET  /console/api/users.csv      same filters, CSV download
 *   GET  /console/api/users/{id}     one user's detail
 *   GET  /console/api/users/{id}/avatar
 *
 * Every /console/api call except login needs the X-Admin-Token header from
 * login. Nothing in THIS controller writes to the database.
 *
 * SecurityConfig must permit "/console" and everything under "/console/" —
 * this controller does its own authentication (AdminConsoleAuth).
 */
@RestController
class AdminConsoleController(
    private val auth: AdminConsoleAuth,
    private val reports: AdminReportService
) {
    data class LoginRequest(val password: String? = null)

    // ── Page + assets ────────────────────────────────────────────────────────

    @GetMapping("/console", "/console/")
    fun page(): ResponseEntity<ByteArray> = asset("index.html", "text/html;charset=UTF-8")

    @GetMapping("/console/console.js")
    fun script(): ResponseEntity<ByteArray> = asset("console.js", "application/javascript;charset=UTF-8")

    @GetMapping("/console/console.css")
    fun styles(): ResponseEntity<ByteArray> = asset("console.css", "text/css;charset=UTF-8")

    private fun asset(name: String, type: String): ResponseEntity<ByteArray> {
        val res = ClassPathResource("console/$name")
        if (!res.exists()) return ResponseEntity.notFound().build()
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(type))
            .cacheControl(CacheControl.noCache())
            .header(
                "Content-Security-Policy",
                "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' blob: data:; " +
                    "connect-src 'self'; font-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"
            )
            .header("X-Robots-Tag", "noindex, nofollow")
            .header("Referrer-Policy", "no-referrer")
            .body(res.inputStream.use { it.readBytes() })
    }

    // ── Auth ─────────────────────────────────────────────────────────────────

    @PostMapping("/console/api/login")
    fun login(@RequestBody(required = false) body: LoginRequest?, req: HttpServletRequest): ResponseEntity<Any> =
        when (val r = auth.login(body?.password, auth.clientIp(req))) {
            is AdminConsoleAuth.LoginResult.Ok ->
                noStore(HttpStatus.OK).body(mapOf("token" to r.token, "expiresAt" to r.expiresAt.toString()))
            is AdminConsoleAuth.LoginResult.Locked ->
                noStore(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, r.retryAfterSeconds.toString())
                    .body(mapOf("error" to "Too many wrong attempts. Try again in ${(r.retryAfterSeconds + 59) / 60} min."))
            AdminConsoleAuth.LoginResult.WrongPassword ->
                noStore(HttpStatus.UNAUTHORIZED).body(mapOf("error" to "Wrong password."))
            AdminConsoleAuth.LoginResult.Disabled ->
                noStore(HttpStatus.SERVICE_UNAVAILABLE).body(
                    mapOf("error" to "Console is off: set ADMIN_PASSWORD on the server (at least ${AdminConsoleAuth.MIN_PASSWORD_LENGTH} characters).")
                )
        }

    @PostMapping("/console/api/logout")
    fun logout(req: HttpServletRequest): ResponseEntity<Any> {
        auth.logout(req)
        return noStore(HttpStatus.OK).body(mapOf("ok" to true))
    }

    // ── Reports ──────────────────────────────────────────────────────────────

    @GetMapping("/console/api/overview")
    fun overview(req: HttpServletRequest): ResponseEntity<Any> =
        guarded(req) { reports.overview() }

    @GetMapping("/console/api/users")
    fun users(
        req: HttpServletRequest,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) bank: String?,
        @RequestParam(required = false) card: String?,
        @RequestParam(required = false) receiver: String?,
        @RequestParam(required = false) suspended: String?,
        @RequestParam(required = false) platform: String?,
        @RequestParam(required = false) active: String?,
        @RequestParam(required = false) joinedFrom: String?,
        @RequestParam(required = false) joinedTo: String?,
        @RequestParam(defaultValue = "joined") sort: String,
        @RequestParam(defaultValue = "desc") dir: String,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int
    ): ResponseEntity<Any> = guarded(req) {
        val f = filter(q, bank, card, receiver, suspended, platform, active, joinedFrom, joinedTo, sort, dir)
        val result = reports.users(f, page, size)
        mapOf("total" to result.total, "page" to page, "size" to size.coerceIn(1, AdminReportService.MAX_PAGE_SIZE), "rows" to result.rows)
    }

    @GetMapping("/console/api/users.csv")
    fun usersCsv(
        req: HttpServletRequest,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) bank: String?,
        @RequestParam(required = false) card: String?,
        @RequestParam(required = false) receiver: String?,
        @RequestParam(required = false) suspended: String?,
        @RequestParam(required = false) platform: String?,
        @RequestParam(required = false) active: String?,
        @RequestParam(required = false) joinedFrom: String?,
        @RequestParam(required = false) joinedTo: String?,
        @RequestParam(defaultValue = "joined") sort: String,
        @RequestParam(defaultValue = "desc") dir: String
    ): ResponseEntity<Any> {
        if (!auth.isAuthorized(req)) return unauthorized()
        val f = filter(q, bank, card, receiver, suspended, platform, active, joinedFrom, joinedTo, sort, dir)
        val rows = reports.usersForExport(f)
        val header = listOf(
            "id", "display_name", "email", "joined_utc", "last_active_utc", "bank", "card_on_file",
            "receives_tips", "suspended", "platforms", "tips_sent", "sent_usd", "tips_received", "received_usd"
        )
        val sb = StringBuilder("﻿") // BOM so Excel reads UTF-8 names correctly
        sb.append(header.joinToString(",")).append("\r\n")
        rows.forEach { r ->
            sb.append(
                listOf(
                    r["id"], r["displayName"], r["email"], r["joined"], r["lastActive"], r["bank"],
                    yn(r["card"]), yn(r["receiver"]), yn(r["suspended"]),
                    (r["platforms"] as? List<*>)?.joinToString(" ") ?: "",
                    r["sentCount"], usd(r["sentCents"]), r["recvCount"], usd(r["recvCents"])
                ).joinToString(",") { csvCell(it) }
            ).append("\r\n")
        }
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(ZoneOffset.UTC).format(Instant.now())
        return noStore(HttpStatus.OK)
            .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"taptaptips-users-$stamp.csv\"")
            .body(sb.toString())
    }

    @GetMapping("/console/api/users/{id}")
    fun user(req: HttpServletRequest, @PathVariable id: String): ResponseEntity<Any> {
        if (!auth.isAuthorized(req)) return unauthorized()
        val uid = runCatching { UUID.fromString(id) }.getOrNull()
            ?: return noStore(HttpStatus.BAD_REQUEST).body(mapOf("error" to "Bad user id"))
        val detail = reports.user(uid) ?: return noStore(HttpStatus.NOT_FOUND).body(mapOf("error" to "User not found"))
        return noStore(HttpStatus.OK).body(detail)
    }

    @GetMapping("/console/api/users/{id}/avatar")
    fun avatar(req: HttpServletRequest, @PathVariable id: String): ResponseEntity<Any> {
        if (!auth.isAuthorized(req)) return unauthorized()
        val uid = runCatching { UUID.fromString(id) }.getOrNull() ?: return ResponseEntity.badRequest().build()
        val bytes = reports.avatar(uid) ?: return ResponseEntity.notFound().build()
        val type = when {
            bytes.size > 3 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> MediaType.IMAGE_PNG
            bytes.size > 3 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() -> MediaType.IMAGE_GIF
            else -> MediaType.IMAGE_JPEG
        }
        return noStore(HttpStatus.OK).contentType(type).body(bytes)
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun guarded(req: HttpServletRequest, block: () -> Any): ResponseEntity<Any> {
        if (!auth.isAuthorized(req)) return unauthorized()
        return noStore(HttpStatus.OK).body(block())
    }

    private fun unauthorized(): ResponseEntity<Any> =
        noStore(HttpStatus.UNAUTHORIZED).body(mapOf("error" to "Not signed in"))

    private fun noStore(status: HttpStatus): ResponseEntity.BodyBuilder =
        ResponseEntity.status(status).cacheControl(CacheControl.noStore()).header("X-Robots-Tag", "noindex, nofollow")

    private fun filter(
        q: String?, bank: String?, card: String?, receiver: String?, suspended: String?, platform: String?,
        active: String?, joinedFrom: String?, joinedTo: String?, sort: String, dir: String
    ) = AdminReportService.UserFilter(
        q = q?.take(200),
        bank = bank, card = card, receiver = receiver, suspended = suspended,
        platform = platform, active = active,
        joinedFrom = joinedFrom?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        joinedTo = joinedTo?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        sort = sort, dir = dir
    )

    private fun yn(v: Any?) = if (v == true) "yes" else "no"

    private fun usd(v: Any?): String {
        val c = (v as? Number)?.toLong() ?: 0L
        return "%d.%02d".format(c / 100, c % 100)
    }

    /** RFC 4180 quoting + spreadsheet formula-injection guard (names are user-typed). */
    private fun csvCell(v: Any?): String {
        var s = v?.toString() ?: ""
        if (s.isNotEmpty() && s[0] in "=+-@\t\r") s = "'$s"
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }
}
