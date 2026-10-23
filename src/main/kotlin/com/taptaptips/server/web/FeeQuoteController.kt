package com.taptaptips.server.web

import com.taptaptips.server.service.FeeBreakdown
import com.taptaptips.server.service.StripePaymentService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal

/**
 * GET /stripe/fees/quote?amountCents=500&coverFees=false
 *   Sender pays $5.00; fees come out of it; receiver gets the rest (default).
 *
 * GET /stripe/fees/quote?amountCents=500&coverFees=true
 *   "Cover the fees": receiver gets (at least) $5.00; the sender pays
 *   $5.00 + fees. The app then sends senderPaysCents as the tip amount —
 *   nothing else about tipping changes (same signature, same charge flow).
 *
 * Uses the same fee formula as the actual charge (calculateFeeBreakdown),
 * so the quote always matches what the receiver ends up with.
 */
@RestController
class FeeQuoteController(private val stripe: StripePaymentService) {

    data class FeeQuote(
        val coverFees: Boolean,
        val senderPaysCents: Long,
        val totalFeeCents: Long,
        val receiverGetsCents: Long
    )

    @GetMapping("/stripe/fees/quote")
    fun quote(
        @RequestParam amountCents: Long,
        @RequestParam(defaultValue = "false") coverFees: Boolean
    ): ResponseEntity<Any> {
        if (amountCents < 1 || amountCents > 100_000) {
            return ResponseEntity.badRequest().body(mapOf("error" to "Invalid amount"))
        }
        val b = if (coverFees) grossUp(amountCents) else calc(amountCents)
        return ResponseEntity.ok(
            FeeQuote(
                coverFees = coverFees,
                senderPaysCents = b.senderPaysCents,
                totalFeeCents = b.totalFeeCents,
                receiverGetsCents = b.receiverGetsCents
            )
        )
    }

    private fun calc(cents: Long): FeeBreakdown =
        stripe.calculateFeeBreakdown(BigDecimal(cents).movePointLeft(2))

    /** Smallest charge whose receiver share is at least [netCents]. */
    fun grossUp(netCents: Long): FeeBreakdown {
        var gross = netCents
        var b = calc(gross)
        var guard = 0
        // Climb by the shortfall until the receiver gets enough.
        while (b.receiverGetsCents < netCents && guard++ < 1000) {
            gross += maxOf(1L, netCents - b.receiverGetsCents)
            b = calc(gross)
        }
        // Step back down while one cent less still covers it (fees round up).
        while (gross > netCents && guard++ < 2000) {
            val lower = calc(gross - 1)
            if (lower.receiverGetsCents < netCents) break
            gross -= 1
            b = lower
        }
        return b
    }
}
