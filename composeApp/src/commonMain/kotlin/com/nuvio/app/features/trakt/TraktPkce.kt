package com.nuvio.app.features.trakt

/**
 * Platform implementations use the operating system's secure random source and SHA-256.
 */
internal expect object TraktPkcePlatform {
    fun createVerifier(): String
    fun sha256(input: ByteArray): ByteArray
}

internal object TraktPkce {
    fun createVerifier(): String = TraktPkcePlatform.createVerifier().also { verifier ->
        require(verifier.length in 43..128)
        require(verifier.all { it in VERIFIER_CHARACTERS })
    }

    fun challengeForVerifier(verifier: String): String {
        require(verifier.length in 43..128)
        require(verifier.all { it in VERIFIER_CHARACTERS })
        return base64UrlNoPadding(TraktPkcePlatform.sha256(verifier.encodeToByteArray()))
    }

    private fun base64UrlNoPadding(bytes: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        return buildString((bytes.size * 4 + 2) / 3) {
            var index = 0
            while (index < bytes.size) {
                val first = bytes[index].toInt() and 0xff
                val hasSecond = index + 1 < bytes.size
                val hasThird = index + 2 < bytes.size
                val second = if (hasSecond) bytes[index + 1].toInt() and 0xff else 0
                val third = if (hasThird) bytes[index + 2].toInt() and 0xff else 0

                append(alphabet[first ushr 2])
                append(alphabet[((first and 0x03) shl 4) or (second ushr 4)])
                if (hasSecond) {
                    append(alphabet[((second and 0x0f) shl 2) or (third ushr 6)])
                }
                if (hasThird) {
                    append(alphabet[third and 0x3f])
                }
                index += 3
            }
        }
    }

    private const val VERIFIER_CHARACTERS =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
}
