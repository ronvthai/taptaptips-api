package com.taptaptips.server.repo

import com.taptaptips.server.domain.Device
import org.springframework.data.jpa.repository.JpaRepository
import java.util.*

interface DeviceRepository : JpaRepository<Device, UUID> {
    fun findAllByUser_Id(userId: UUID): List<Device>
    fun findByIdAndUser_Id(deviceId: UUID, userId: UUID): Device?
    fun findByUser_IdAndPublicKey(userId: UUID, publicKey: ByteArray): Device?
}
