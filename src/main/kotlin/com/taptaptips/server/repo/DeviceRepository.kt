package com.taptaptips.server.repo

import com.taptaptips.server.domain.Device
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface DeviceRepository : JpaRepository<Device, UUID> {
    fun findByUser_IdAndPublicKey(userId: UUID, publicKey: ByteArray): Device?
    fun findAllByUser_Id(userId: UUID): List<Device>
    
    // NEW: Find all active devices for a user
    fun findAllByUser_IdAndIsActive(userId: UUID, isActive: Boolean): List<Device>
    
    // NEW: Find devices by user and device name starting with prefix
    fun findAllByUser_IdAndDeviceNameStartingWith(userId: UUID, prefix: String): List<Device>
}