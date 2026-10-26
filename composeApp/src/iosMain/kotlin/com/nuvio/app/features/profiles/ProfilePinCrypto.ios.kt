package com.nuvio.app.features.profiles

import com.nuvio.app.features.plugins.cryptointerop.CC_SHA256
import com.nuvio.app.features.plugins.cryptointerop.CC_SHA256_DIGEST_LENGTH
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault
import platform.Security.errSecSuccess
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.refTo
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.addressOf

actual object ProfilePinCrypto {
    @OptIn(ExperimentalForeignApi::class)
    actual fun randomBytes(size: Int): ByteArray {
        require(size >= 0)
        val output = ByteArray(size)
        if (size == 0) return output
        val status = output.usePinned { pinned ->
            SecRandomCopyBytes(kSecRandomDefault, size.toULong(), pinned.addressOf(0))
        }
        check(status == errSecSuccess) { "Unable to generate secure random bytes" }
        return output
    }

    @OptIn(ExperimentalForeignApi::class)
    actual fun sha256Hex(value: String): String {
        val input = value.encodeToByteArray()
        val output = UByteArray(CC_SHA256_DIGEST_LENGTH.toInt())
        CC_SHA256(input.refTo(0), input.size.toUInt(), output.refTo(0))
        return output.joinToString(separator = "") { byte ->
            byte.toString(16).padStart(2, '0')
        }
    }
}