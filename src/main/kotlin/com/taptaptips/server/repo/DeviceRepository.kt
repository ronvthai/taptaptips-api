package com.taptaptips.server.repo

import com.taptaptips.server.domain.Device
import org.springframework.data.jpa.repository.JpaRepository
import java.util.*

interface DeviceRepository : JpaRepository<Device, UUID>
