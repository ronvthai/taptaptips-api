package com.taptaptips.server.web

import com.taptaptips.server.domain.AppUser
import com.taptaptips.server.repo.AppUserRepository
import com.taptaptips.server.security.JwtService
import org.springframework.http.ResponseEntity
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/auth")
class AuthController(
    private val userRepo: AppUserRepository,
    private val jwt: JwtService
) {
    private val pwd: PasswordEncoder = BCryptPasswordEncoder()

    data class RegisterReq(val email: String, val password: String, val displayName: String)
    data class LoginReq(val email: String, val password: String)

    @PostMapping("/register")
    fun register(@RequestBody r: RegisterReq): ResponseEntity<Any> {
        if (userRepo.existsByEmail(r.email)) return ResponseEntity.status(409).body("email exists")
        val u = userRepo.save(AppUser(email = r.email, passwordHash = pwd.encode(r.password), displayName = r.displayName))
        return ResponseEntity.ok(mapOf(
            "accessToken" to jwt.issueAccess(u.id), 
            "userId" to u.id.toString(),
            "username" to u.displayName
        ))
    }

    @PostMapping("/login")
    fun login(@RequestBody r: LoginReq): ResponseEntity<Any> {
        val u = userRepo.findByEmail(r.email) ?: return ResponseEntity.status(401).body("bad credentials")
        if (!pwd.matches(r.password, u.passwordHash)) return ResponseEntity.status(401).body("bad credentials")
        return ResponseEntity.ok(mapOf(
            "accessToken" to jwt.issueAccess(u.id), 
            "userId" to u.id.toString(),
            "username" to u.displayName
        ))
    }
}