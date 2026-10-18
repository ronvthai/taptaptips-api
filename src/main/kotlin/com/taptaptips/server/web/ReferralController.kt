package com.taptaptips.server.web

import com.taptaptips.server.repo.AppUserRepository
import com.taptaptips.server.service.ReferralService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/referrals")
class ReferralController(
    private val referrals: ReferralService,
    private val users: AppUserRepository
) {
    private fun authUserId(): UUID =
        UUID.fromString(SecurityContextHolder.getContext().authentication.name)

    /**
     * GET /referrals/config — PUBLIC (no login). The apps call this to decide
     * whether to show the gift icon and the sign-up code field at all, so the
     * program can be switched on/off from Render with no app update.
     */
    @GetMapping("/config")
    fun config(): ReferralService.PublicConfig = referrals.publicConfig()

    /** GET /referrals/me — the caller's code, share text and earnings. */
    @GetMapping("/me")
    fun me(): ReferralService.Summary = referrals.summaryFor(authUserId())

    data class ApplyReq(val code: String?)

    /**
     * POST /referrals/apply — enter a code shortly after signing up (for people
     * who skipped the field on the sign-up screen).
     */
    @PostMapping("/apply")
    fun apply(@RequestBody body: ApplyReq): ResponseEntity<Any> {
        val user = users.findById(authUserId()).orElseThrow()
        val error = referrals.applyCode(user, body.code)
        return if (error == null) ResponseEntity.ok(mapOf("status" to "APPLIED"))
        else ResponseEntity.badRequest().body(mapOf("error" to error))
    }
}
