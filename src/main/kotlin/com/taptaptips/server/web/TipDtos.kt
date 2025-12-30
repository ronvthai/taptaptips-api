package com.taptaptips.server.web

import java.math.BigDecimal
import java.util.*

data class CreateTipRequest(
    val senderId: UUID,
    val receiverId: UUID,
    val amount: BigDecimal,
    val nonce: String,
    val timestamp: Long,
    val signature: String,
    val deviceId: String?, // must be provided by client
    val timezone: String   // ⭐ NEW: User's timezone (e.g., "America/Los_Angeles")
)

data class TipResult(val status: String) // "CONFIRMED" | "DUPLICATE"
