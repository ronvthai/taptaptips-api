package com.taptaptips.server.security

import com.taptaptips.server.repo.AppUserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Answers "is this user suspended?" for every authenticated request without
 * a database hit each time.
 *
 * Results are cached for [TTL_MS], so suspending someone (in the database or
 * from the Stripe webhook) locks them out within about a minute. The query
 * reads only the `suspended` column, not the whole user row (which includes
 * the profile picture).
 *
 * If the database can't be reached we let the request through rather than
 * lock every user out; the login and refresh checks still apply.
 */
@Component
class SuspensionGuard(private val users: AppUserRepository) {

    private val log = LoggerFactory.getLogger(SuspensionGuard::class.java)

    private data class Entry(val suspended: Boolean, val checkedAt: Long)
    private val cache = ConcurrentHashMap<UUID, Entry>()

    fun isSuspended(userId: UUID): Boolean {
        val now = System.currentTimeMillis()
        cache[userId]?.let { if (now - it.checkedAt < TTL_MS) return it.suspended }

        val suspended = try {
            users.findSuspendedById(userId) ?: false
        } catch (e: Exception) {
            log.warn("Suspension check failed for $userId: ${e.message}")
            return cache[userId]?.suspended ?: false
        }
        if (cache.size > MAX_ENTRIES) cache.clear()
        cache[userId] = Entry(suspended, now)
        return suspended
    }

    companion object {
        const val TTL_MS = 60_000L
        private const val MAX_ENTRIES = 50_000
    }
}
