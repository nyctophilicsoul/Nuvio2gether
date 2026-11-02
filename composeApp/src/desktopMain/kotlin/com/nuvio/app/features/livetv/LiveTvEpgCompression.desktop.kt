package com.nuvio.app.features.livetv

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import org.tukaani.xz.XZInputStream

internal actual fun decompressLiveTvEpgPayload(
    compression: LiveTvEpgCompression,
    payload: ByteArray,
    maxOutputBytes: Int,
    onProgress: () -> Unit,
): LiveTvDecodedEpgPayload {
    if (compression == LiveTvEpgCompression.Plain) {
        require(payload.size <= maxOutputBytes) { "XMLTV payload exceeds the safe size limit." }
        return LiveTvDecodedEpgPayload(payload, payload.size)
    }
    if (compression == LiveTvEpgCompression.Zip) {
        val selected = findXmlEntry(payload) ?: error("ZIP archive does not contain an XMLTV file.")
        return ZipInputStream(ByteArrayInputStream(payload)).use { input ->
            while (true) {
                onProgress()
                val entry = input.nextEntry ?: error("ZIP XMLTV entry could not be read.")
                if (!entry.isDirectory && entry.name == selected) {
                    return@use input.readBytesWithLimit(maxOutputBytes, onProgress)
                }
            }
            error("ZIP XMLTV entry could not be read.")
        }
    }
    val input = ByteArrayInputStream(payload)
    val decoded = when (compression) {
        LiveTvEpgCompression.Plain -> input
        LiveTvEpgCompression.Gzip -> GZIPInputStream(input)
        LiveTvEpgCompression.Xz -> XZInputStream(input, 72 * 1024)
        LiveTvEpgCompression.Zip -> error("ZIP is handled before stream selection")
    }
    return decoded.use { it.readBytesWithLimit(maxOutputBytes, onProgress) }
}

private fun findXmlEntry(payload: ByteArray): String? =
    ZipInputStream(ByteArrayInputStream(payload)).use { input ->
        var first: String? = null
        while (true) {
            val entry = input.nextEntry ?: return@use first
            if (!entry.isDirectory) {
                if (first == null) first = entry.name
                val name = entry.name.lowercase()
                if (name.endsWith(".xml") || name.endsWith(".xmltv")) return@use entry.name
            }
        }
        null
    }

private fun InputStream.readBytesWithLimit(
    maxBytes: Int,
    onProgress: () -> Unit,
): LiveTvDecodedEpgPayload {
    var output = ByteArray(minOf(8192, maxBytes).coerceAtLeast(1))
    var total = 0
    while (true) {
        onProgress()
        if (total == output.size) {
            if (output.size == maxBytes) {
                require(read() < 0) { "Decompressed XMLTV payload exceeds the safe size limit." }
                break
            }
            output = output.copyOf(minOf(maxBytes, output.size * 2))
        }
        val count = read(output, total, output.size - total)
        if (count < 0) break
        if (count == 0) continue
        total += count
    }
    return LiveTvDecodedEpgPayload(output, total)
}
