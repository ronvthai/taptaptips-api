package com.taptaptips.server.web

import com.taptaptips.server.domain.Device
import com.taptaptips.server.repo.AppUserRepository
import com.taptaptips.server.repo.DeviceRepository
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.*
import java.util.Base64

@RestController
@RequestMapping("/devices")
class DeviceController(
    private val deviceRepo: DeviceRepository,
    private val userRepo: AppUserRepository
) {
    data class RegisterDeviceReq(val userId: UUID, val deviceName: String?, val publicKeyBase64: String)

    @PostMapping
    fun register(@RequestBody r: RegisterDeviceReq): ResponseEntity<Any> {
        val u = userRepo.findById(r.userId).orElseThrow()
        val d = deviceRepo.save(Device(user = u, deviceName = r.deviceName, publicKey = Base64.getDecoder().decode(r.publicKeyBase64)))
        return ResponseEntity.ok(mapOf("deviceId" to d.id))
    }
}
