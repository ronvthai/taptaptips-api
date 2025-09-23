package com.taptaptips.server.web

data class BankSummary(
    val linked: Boolean,
    val last4: String? = null,
    val accountType: String? = null,
    val institution: String? = null
)

data class ManualLinkRequest(
    val holderName: String,
    val accountType: String,     // CHECKING | SAVINGS
    val routingNumber: String,
    val accountNumber: String,
    val institutionName: String?
)
