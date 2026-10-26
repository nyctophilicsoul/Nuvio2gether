package com.nuvio.app.features.profiles

import java.security.MessageDigest
import java.security.SecureRandom

actual object ProfilePinCrypto {
    actual fun randomBytes(size: Int): ByteArray = ByteArray(size).also { SecureRandom().nextBytes(it) }

    actual fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray())
        return digest.joinToString(separator = "") { byte ->
            byte.toUByte().toString(16).padStart(2, '0')
        }
    }
}