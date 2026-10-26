package com.nuvio.app.features.trakt

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CommonCrypto.CC_SHA256
import platform.CommonCrypto.CC_SHA256_DIGEST_LENGTH
import platform.Foundation.NSUUID

@OptIn(ExperimentalForeignApi::class)
internal actual object TraktPkcePlatform {
    actual fun createVerifier(): String = buildString(128) {
        // NSUUID uses the platform random source; the hex form is valid PKCE input.
        repeat(4) {
            append(NSUUID().UUIDString.replace("-", ""))
        }
    }

    actual fun sha256(input: ByteArray): ByteArray {
        val digest = ByteArray(CC_SHA256_DIGEST_LENGTH)
        input.usePinned { inputPinned ->
            digest.usePinned { digestPinned ->
                CC_SHA256(
                    inputPinned.addressOf(0),
                    input.size.convert(),
                    digestPinned.addressOf(0),
                )
            }
        }
        return digest
    }
}
