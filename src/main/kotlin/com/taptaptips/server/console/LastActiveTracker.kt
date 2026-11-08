package com.taptaptips.server.console

import com.taptaptips.server.security.ATTR_VERIFIED_USER_ID
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Records when each user last used the app, for the admin console's
 * "Last active" column.
 *
 * - Adds `app_user.last_active_at` on startup (idempotent, like ReferralSchema).
 *   It's not mapped on the AppUser entity, so nothing else in the app changes.
 * - After any successful request made with a valid user login, stamps
 *   last_active_at = now() — at most once every 10 minutes per user, on a
 *   background thread, so it adds no latency and almost no DB load.
 *
 * Users who haven't opened the app since this shipped have no value yet; the
 * console falls back to their latest device check-in, push-token refresh or
 * tip, so the column is useful from day one.
 */
@Component
class LastActiveTracker(private val jdbc: JdbcTemplate) : HandlerInterceptor {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val THROTTLE_MS = 10 * 60_000L
    }

    /** True once last_active_at exists; reports skip the column until then. */
    @Volatile var ready = false
        protected set   // protected, not private: Spring's Kotlin plugin makes this class open
    private val lastStamp = ConcurrentHashMap<UUID, Long>()
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "last-active-writer").apply { isDaemon = true }
    }

    @EventListener(ApplicationReadyEvent::class)
    fun ensureColumn() {
        try {
            jdbc.execute("ALTER TABLE app_user ADD COLUMN IF NOT EXISTS last_active_at timestamptz")
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_app_user_last_active ON app_user (last_active_at)")
            ready = true
            log.info("✅ last_active_at tracking ready")
        } catch (e: Exception) {
            log.error("❌ Could not add app_user.last_active_at — last-active tracking is off", e)
        }
    }

    override fun afterCompletion(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
        ex: Exception?
    ) {
        if (!ready || ex != null || response.status >= 400) return
        val raw = request.getAttribute(ATTR_VERIFIED_USER_ID) as? String ?: return
        val uid = runCatching { UUID.fromString(raw) }.getOrNull() ?: return

        val now = System.currentTimeMillis()
        val prev = lastStamp[uid]
        if (prev != null && now - prev < THROTTLE_MS) return
        lastStamp[uid] = now
        if (lastStamp.size > 200_000) lastStamp.clear()

        writer.execute {
            try {
                jdbc.update("UPDATE app_user SET last_active_at = now() WHERE id = ?", uid)
            } catch (e: Exception) {
                log.debug("last_active_at update failed for $uid: ${e.message}")
            }
        }
    }
}

@Configuration
class LastActiveWebConfig(private val tracker: LastActiveTracker) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(tracker)
            .excludePathPatterns("/console/**", "/webhooks/**", "/actuator/**", "/.well-known/**")
    }
}
