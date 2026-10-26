package com.nuvio.app.features.trakt

import java.security.MessageDigest
import java.util.UUID

internal actual object TraktPkcePlatform {
    actual fun createVerifier(): String = buildString(128) {
        // UUID.randomUUID() is backed by SecureRandom; four UUIDs provide a
        // 128-character verifier made only from RFC 7636 unreserved characters.
        repeat(4) {
            append(UUID.randomUUID().toString().replace("-", ""))
        }
    }

    actual fun sha256(input: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(input)
}
