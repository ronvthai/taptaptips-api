package com.taptaptips.server.security

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@Component
class CryptoService(@Value("\${crypto.bank.key-base64}") keyB64: String) {
    private val key: SecretKey = SecretKeySpec(Base64.getDecoder().decode(keyB64), "AES")
    private val rng = SecureRandom()

    fun encrypt(plain: String): String {
        val iv = ByteArray(12).also(rng::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        }
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + ct)      // store iv||ciphertext
    }

    fun decrypt(blob: String): String {
        val all = Base64.getDecoder().decode(blob)
        val iv = all.copyOfRange(0, 12)
        val ct = all.copyOfRange(12, all.size)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        }
        return String(c.doFinal(ct), Charsets.UTF_8)
    }
}
