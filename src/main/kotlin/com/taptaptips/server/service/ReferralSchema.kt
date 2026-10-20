package com.taptaptips.server.service

import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * Database guard for "one reward per physical card".
 *
 * JPA's @Index can't express a partial unique index, and Hibernate's
 * ddl-auto won't create one, so it's created here on startup (idempotent —
 * IF NOT EXISTS). Same SQL as db/referral_fix.sql if you'd rather run it by hand.
 *
 * Runs on ApplicationReadyEvent so Hibernate has already created/updated the
 * `referral` table on a fresh database.
 *
 * If the index can't be created (most likely because existing rows already
 * share a rewarded fingerprint), [ready] stays false and ReferralService
 * refuses to qualify anyone — referrals stay PENDING rather than risk paying
 * twice for one card. Fix the duplicates (query in the log) and restart.
 */
@Component
class ReferralSchema(private val jdbc: JdbcTemplate) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val INDEX_NAME = "ux_referral_card_rewarded"
    }

    @Volatile
    var ready: Boolean = false
            protected set
    @EventListener(ApplicationReadyEvent::class)
    fun ensure() {
        try {
            jdbc.execute("ALTER TABLE referral ADD COLUMN IF NOT EXISTS qualifying_tip_id uuid")
            jdbc.execute(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS $INDEX_NAME
                    ON referral (card_fingerprint)
                    WHERE status IN ('QUALIFIED', 'PAYING', 'PAID')
                """.trimIndent()
            )
            ready = true
            log.info("✅ Referral schema ready ($INDEX_NAME)")
        } catch (e: Exception) {
            log.error(
                "❌ Could not create $INDEX_NAME — referral qualification is DISABLED until fixed. " +
                        "Find duplicates with: SELECT card_fingerprint, array_agg(id) FROM referral " +
                        "WHERE status IN ('QUALIFIED','PAYING','PAID') GROUP BY card_fingerprint HAVING COUNT(*) > 1",
                e
            )
        }
    }
}
