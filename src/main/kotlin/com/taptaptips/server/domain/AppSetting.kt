package com.taptaptips.server.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * One runtime setting changed from the admin page (e.g. referral.enabled = false).
 * A row only exists while a setting is overridden; deleting it ("Reset") goes
 * back to the Render env var / built-in default. See RuntimeSettings.
 */
@Entity
@Table(name = "app_setting")
class AppSetting(
    @Id
    @Column(name = "setting_key", length = 100)
    val key: String,

    @Column(name = "setting_value", nullable = false, length = 500)
    var value: String,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),

    @Column(name = "updated_by", length = 100)
    var updatedBy: String? = null
)
