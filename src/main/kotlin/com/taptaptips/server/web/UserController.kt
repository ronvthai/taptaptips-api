package com.taptaptips.server.web

import com.taptaptips.server.repo.AppUserRepository
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.security.MessageDigest
import java.time.Duration
import java.util.*

@RestController
@RequestMapping("/users")
class UserController(private val users: AppUserRepository) {

    @PutMapping("/{id}/avatar", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadAvatar(
        @PathVariable id: UUID,
        @RequestParam("file") file: MultipartFile
    ): ResponseEntity<Any> {
        if (file.isEmpty) return ResponseEntity.badRequest().body("empty file")
        if (file.size > 2_000_000) return ResponseEntity.badRequest().body("file too large")

        val u = users.findById(id).orElseThrow()
        u.profilePicture = file.bytes       // <-- entity property
        u.updatedAt = java.time.Instant.now()
        users.save(u)
        return ResponseEntity.ok(mapOf("status" to "OK"))
    }

    @GetMapping("/{id}/avatar")
    fun getAvatar(@PathVariable id: UUID): ResponseEntity<ByteArray> {
        val u = users.findById(id).orElseThrow()
        val bytes = u.profilePicture ?: return ResponseEntity.notFound().build()

        val contentType = MediaType.IMAGE_JPEG
        val md5 = MessageDigest.getInstance("MD5").digest(bytes)
        val etag = md5.joinToString("") { "%02x".format(it) }

        return ResponseEntity.ok()
            .contentType(contentType)
            .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic())
            .eTag(etag)
            .header(HttpHeaders.CONTENT_LENGTH, bytes.size.toString())
            .body(bytes)
    }
    // in UserController.kt
    data class UpdateUserReq(
        val email: String?,
        val displayName: String?,
        val password: String?
    )

    @PatchMapping("/{id}")
    fun updateUser(
        @PathVariable id: UUID,
        @RequestBody body: UpdateUserReq
    ): ResponseEntity<Any> {
        val u = users.findById(id).orElseThrow()

        var changed = false
        body.email?.let {
            // (optional) reject if email already taken
            if (u.email != it && users.existsByEmail(it)) {
                return ResponseEntity.status(409).body("email exists")
            }
            // JPA entity email is val in your model; make it var if you want to allow change
            // or rebuild a new entity. Let's rebuild:
            u.apply { /* handled below by copy-style rebuild */ }
            changed = true
        }
        body.displayName?.let { changed = true }
        body.password?.let { changed = true }

        if (!changed) return ResponseEntity.ok(mapOf("status" to "NOOP"))

        // Rebuild entity with new fields (since email/displayName/passwordHash are vals)
        val encoder = org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
        val newEntity = com.taptaptips.server.domain.AppUser(
            id          = u.id,
            email       = body.email ?: u.email,
            passwordHash= body.password?.let { encoder.encode(it) } ?: u.passwordHash,
            displayName = body.displayName ?: u.displayName,
            createdAt   = u.createdAt,
            updatedAt   = java.time.Instant.now(),
            profilePicture = u.profilePicture
        )

        users.save(newEntity)
        return ResponseEntity.ok(mapOf("status" to "OK"))
    }

}
