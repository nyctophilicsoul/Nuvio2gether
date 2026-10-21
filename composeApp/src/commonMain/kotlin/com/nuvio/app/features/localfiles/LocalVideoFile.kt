package com.nuvio.app.features.localfiles

import androidx.compose.runtime.Composable

/**
 * A locally readable video reference. [uri] is device-local and must never be sent to WatchParty.
 *
 * The match key uses only filename and size so that a participant can find their own copy without
 * exchanging a filesystem/content URI or uploading media.
 */
data class LocalVideoFile(
    val uri: String,
    val displayName: String,
    val sizeBytes: Long,
    val durationMs: Long? = null,
    val modifiedAtMs: Long? = null,
    val folderName: String? = null,
) {
    val matchKey: String
        get() = localVideoMatchKey(displayName, sizeBytes)
}

const val LOCAL_FILE_META_TYPE = "local_file"

fun localVideoMatchKey(displayName: String, sizeBytes: Long): String {
    val normalizedName = displayName
        .substringAfterLast('/')
        .trim()
        .lowercase()
        .replace(Regex("\\s+"), " ")
    val identity = "$normalizedName|${sizeBytes.coerceAtLeast(0L)}"
    // Stable, platform-independent 64-bit FNV-1a. This is a matching identifier, not a security
    // hash; it contains no URI/path and is used only to find the same named copy with same size.
    var hash = -3750763034362895579L
    identity.forEach { character ->
        hash = (hash xor character.code.toLong()) * 1099511628211L
    }
    return "local:${hash.toString(16)}"
}

/** Platform implementation: Android scans MediaStore and user-granted SAF trees. */
@Composable
expect fun LocalFilesScreen(
    onBack: () -> Unit,
    preferredMatchKey: String?,
    onPlay: (LocalVideoFile) -> Unit,
)
