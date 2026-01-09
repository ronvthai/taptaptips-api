package com.taptaptips.server.domain

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "app_user")
open class AppUser(
    @Id 
    val id: UUID = UUID.randomUUID(),

    @Column(nullable = false, unique = true, columnDefinition = "citext")
    var email: String = "",

    @Column(nullable = false, name = "password_hash")
    var passwordHash: String = "",

    @Column(nullable = false, name = "display_name")
    var displayName: String = "",

    @Column(nullable = false, name = "created_at")
    val createdAt: Instant = Instant.now(),

    @Column(nullable = false, name = "updated_at")
    var updatedAt: Instant = Instant.now(),
    
    @Basic(fetch = FetchType.LAZY)
    @Column(name = "profile_picture", columnDefinition = "bytea")
    var profilePicture: ByteArray? = null,
    
    // ===== STRIPE RECEIVER FIELDS =====
    // These fields are for users who RECEIVE tips
    
    @Column(nullable = false, name = "wants_to_receive_tips")
    var wantsToReceiveTips: Boolean = false,
    
    @Column(unique = true, name = "stripe_account_id")
    var stripeAccountId: String? = null,
    
    @Column(nullable = false, name = "stripe_onboarded")
    var stripeOnboarded: Boolean = false,
    
    @Column(name = "stripe_last_checked")
    var stripeLastChecked: Instant? = null,
    
    @Column(name = "bank_last4", length = 4)
    var bankLast4: String? = null,
    
    @Column(name = "bank_name")
    var bankName: String? = null,
    
    // ===== STRIPE SENDER FIELDS (NEW) =====
    // These fields are for users who SEND tips
    
    @Column(unique = true, name = "stripe_customer_id")
    var stripeCustomerId: String? = null,
    
    @Column(name = "default_payment_method_id")
    var defaultPaymentMethodId: String? = null,  // ⭐ ADD THIS LINE

    // Add these to your existing AppUser entity
    @Column(name = "suspended")
    var suspended: Boolean = false,

    @Column(name = "suspension_reason")
    var suspensionReason: String? = null,

    @Column(name = "suspended_at")
    var suspendedAt: Instant? = null
)