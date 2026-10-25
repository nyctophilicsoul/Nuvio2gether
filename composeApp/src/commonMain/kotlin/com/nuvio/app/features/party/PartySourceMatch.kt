package com.nuvio.app.features.party

import com.nuvio.app.features.streams.StreamItem

/**
 * Best-effort metadata match for the picker label only.
 *
 * An add-on ID plus a display label is not source identity: one add-on can return
 * multiple encodes under the same label. Exact host-source playback is handled by
 * PartyStream sharing, not by guessing from labels.
 */
internal fun PartySource.matches(stream: StreamItem): Boolean {
    val hash = infoHash?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    val streamHash = stream.p2pInfoHash?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    // If either side claims torrent identity, require matching torrent identity.
    // Never fall through to filenames or labels when the hash is missing or differs.
    if (hash != null || streamHash != null) {
        if (hash == null || streamHash == null || hash != streamHash) return false

        val sourceIndex = fileIdx
        val streamIndex = stream.p2pFileIdx
        if (sourceIndex != null && streamIndex != null) {
            return sourceIndex == streamIndex
        }

        // For multi-file torrents, a shared hash alone is not enough.
        // If either index is missing, require the filenames to independently match.
        val file = filename?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val streamFile = (stream.behaviorHints.filename ?: stream.clientResolve?.filename)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return false
        return normalizePartyFilename(file) == normalizePartyFilename(streamFile)
    }

    val sourceVideoHash = videoHash?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    val streamVideoHash = stream.behaviorHints.videoHash?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    if (sourceVideoHash != null || streamVideoHash != null) {
        return sourceVideoHash != null && streamVideoHash != null && sourceVideoHash == streamVideoHash
    }

    val file = filename?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    val streamFile = (stream.behaviorHints.filename ?: stream.clientResolve?.filename)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: return false

    return normalizePartyFilename(file) == normalizePartyFilename(streamFile)
}

private fun normalizePartyFilename(value: String): String =
    value.substringAfterLast('/').substringAfterLast('\\').trim().lowercase()
