package com.taptaptips.server.security

import org.springframework.stereotype.Component
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

@Component
class Ed25519VerifierByteKey {
    fun verify(publicKeyBytes: ByteArray, message: ByteArray, signatureB64: String): Boolean {
        val spki = if (publicKeyBytes.size == 32) wrapRawToX509(publicKeyBytes) else publicKeyBytes
        val kf = KeyFactory.getInstance("Ed25519")
        val pub = kf.generatePublic(X509EncodedKeySpec(spki))
        val sig = Signature.getInstance("Ed25519")
        sig.initVerify(pub)
        sig.update(message)
        val sigBytes = Base64.getDecoder().decode(signatureB64)
        return sig.verify(sigBytes)
    }

    // SPKI header for Ed25519: 302a300506032b6570032100
    private fun wrapRawToX509(raw32: ByteArray): ByteArray {
        val prefix = byteArrayOf(
            0x30,0x2a,0x30,0x05,0x06,0x03,0x2b,0x65,0x70,0x03,0x21,0x00
        )
        return prefix + raw32
    }
}
