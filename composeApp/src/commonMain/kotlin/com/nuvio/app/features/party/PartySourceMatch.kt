package com.nuvio.app.features.party

import com.nuvio.app.features.streams.StreamItem

internal fun PartySource.matches(stream: StreamItem): Boolean {
    val hash = infoHash?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    val streamHash = stream.p2pInfoHash?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    if (hash != null && streamHash != null) {
        // A known hash mismatch is definitive: don't fall back to a coincidentally
        // identical filename or display label from another source.
        if (hash != streamHash) return false

        val sourceIndex = fileIdx
        val streamIndex = stream.p2pFileIdx
        if (sourceIndex != null && streamIndex != null) {
            return sourceIndex == streamIndex
        }
        // Same torrent hash alone is not enough to identify an episode in a
        // multi-file torrent. Try filename/label matching if the index is absent.
    }

    val file = filename?.trim()?.takeIf { it.isNotEmpty() }
    val streamFile = (stream.behaviorHints.filename ?: stream.clientResolve?.filename)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    if (file != null && streamFile != null) {
        return normalizePartyFilename(file) == normalizePartyFilename(streamFile)
    }

    val sourceAddonId = addonId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    val label = name?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    return stream.addonId == sourceAddonId &&
        stream.streamLabel.trim().equals(label, ignoreCase = true)
}

private fun normalizePartyFilename(value: String): String =
    value.substringAfterLast('/').substringAfterLast('\\').trim().lowercase()
