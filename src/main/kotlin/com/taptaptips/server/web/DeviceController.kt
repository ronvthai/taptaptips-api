package com.taptaptips.server.web

import com.taptaptips.server.domain.Device
import com.taptaptips.server.repo.AppUserRepository
import com.taptaptips.server.repo.DeviceRepository
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.util.*
import java.util.Base64

@RestController
@RequestMapping("/devices")
class DeviceController(
    private val deviceRepo: DeviceRepository,
    private val userRepo: AppUserRepository
) {
    private val log = LoggerFactory.getLogger(DeviceController::class.java)
    
    data class RegisterDeviceReq(
        val userId: UUID, 
        val deviceName: String?, 
        val publicKeyBase64: String
    )

    @PostMapping
    fun register(@RequestBody r: RegisterDeviceReq): ResponseEntity<Any> {
        val user = userRepo.findById(r.userId).orElseThrow { 
            IllegalArgumentException("User not found: ${r.userId}") 
        }
        
        val publicKeyBytes = try {
            Base64.getDecoder().decode(r.publicKeyBase64)
        } catch (e: Exception) {
            log.error("Invalid base64 public key", e)
            return ResponseEntity.badRequest().body(mapOf("error" to "Invalid public key format"))
        }
        
        // Check if this EXACT public key is already registered for this user
        val existing = deviceRepo.findByUser_IdAndPublicKey(r.userId, publicKeyBytes)
        
        if (existing != null) {
            // Update device name and last seen if provided
            if (r.deviceName != null && existing.deviceName != r.deviceName) {
                existing.deviceName = r.deviceName
            }
            existing.lastSeen = Instant.now()
            existing.updatedAt = Instant.now()
            deviceRepo.save(existing)
            log.info("✅ Updated existing device: ${existing.id}")
            
            return ResponseEntity.ok(mapOf(
                "deviceId" to existing.id,
                "status" to "EXISTS"
            ))
        }
        
        // NEW LOGIC: Check for devices with similar name pattern (reinstall detection)
        if (r.deviceName != null && r.deviceName.contains("-")) {
            // Extract prefix (e.g., "TapTapTips" from "TapTapTips-a1b2")
            val prefix = r.deviceName.substringBefore("-")
            
            // Find all devices with this prefix for this user
            val existingByName = deviceRepo.findAllByUser_IdAndDeviceNameStartingWith(r.userId, prefix)
            
            log.debug("🔍 Found ${existingByName.size} device(s) with prefix '$prefix' for user ${r.userId}")
            
            // DELETE old devices that DON'T have the current public key
            existingByName.forEach { oldDevice ->
                // Skip if it's the same key (shouldn't happen, but just in case)
                if (oldDevice.publicKey.contentEquals(publicKeyBytes)) {
                    return@forEach
                }
                
                // Delete the old device
                deviceRepo.delete(oldDevice)
                log.warn("🗑️ Deleted old device ${oldDevice.id} (${oldDevice.deviceName}) for user ${r.userId} - reinstall detected")
            }
        }
        
        // Create new device
        val newDevice = deviceRepo.save(
            Device(
                user = user, 
                deviceName = r.deviceName, 
                publicKey = publicKeyBytes,
                lastSeen = Instant.now()
            )
        )
        
        log.info("✅ Registered new device: ${newDevice.id} (${newDevice.deviceName}) for user: ${r.userId}")
        log.debug("Public key (first 20 chars): ${r.publicKeyBase64.take(20)}...")
        
        return ResponseEntity.ok(mapOf(
            "deviceId" to newDevice.id,
            "status" to "CREATED"
        ))
    }
}