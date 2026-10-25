package com.nuvio.app.features.party

import com.nuvio.app.features.streams.StreamItem

internal fun PartySource.matches(stream: StreamItem): Boolean {
    val hash = infoHash?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    val streamHash = stream.p2pInfoHash?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    // Torrent identity is authoritative when both sides expose it.
    if (hash != null && streamHash != null) {
        if (hash != streamHash) return false

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

    val file = filename?.trim()?.takeIf { it.isNotEmpty() }
    val streamFile = (stream.behaviorHints.filename ?: stream.clientResolve?.filename)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    // HTTP streams often lack torrent identity. Matching filenames is a strong
    // signal, but filename values can vary between addons or after URL resolution.
    if (file != null && streamFile != null &&
        normalizePartyFilename(file) == normalizePartyFilename(streamFile)
    ) {
        return true
    }

    // Don't return false just because both filenames exist but differ. For ordinary
    // HTTP streams, prefer the stable provider + stream label as a fallback identity.
    val sourceAddonId = addonId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    val label = name?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    return stream.addonId.trim() == sourceAddonId &&
        stream.streamLabel.trim().equals(label, ignoreCase = true)
}

private fun normalizePartyFilename(value: String): String =
    value.substringAfterLast('/').substringAfterLast('\\').trim().lowercase()
