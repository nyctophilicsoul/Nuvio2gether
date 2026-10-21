package com.nuvio.app.features.localfiles

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

private const val PREFS_NAME = "nuvio_local_files"
private const val FOLDERS_KEY = "persisted_tree_uris"
private const val MAX_TREE_DEPTH = 12
private const val MAX_TREE_FILES = 10000
private val VIDEO_EXTENSIONS = setOf("3g2", "3gp", "asf", "avi", "flv", "m2ts", "m4v", "mkv", "mov", "mp4", "mpeg", "mpg", "mts", "ogv", "ts", "vob", "webm", "wmv")

@Composable
actual fun LocalFilesScreen(
    onBack: () -> Unit,
    preferredMatchKey: String?,
    onPlay: (LocalVideoFile) -> Unit,
) {
    val context = LocalContext.current
    var folders by remember(context) { mutableStateOf(loadTreeUris(context)) }
    var deviceFiles by remember { mutableStateOf(emptyList<LocalVideoFile>()) }
    var folderFiles by remember { mutableStateOf(emptyList<LocalVideoFile>()) }
    var isDeviceLoading by remember { mutableStateOf(false) }
    var isFolderLoading by remember { mutableStateOf(false) }
    val isLoading = isDeviceLoading || isFolderLoading
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var deviceScanRequest by remember { mutableIntStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val granted = grants[Manifest.permission.READ_MEDIA_VIDEO] == true ||
            grants[Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED] == true ||
            grants[Manifest.permission.READ_EXTERNAL_STORAGE] == true
        if (granted) {
            deviceScanRequest += 1
        } else {
            errorMessage = "Video-library permission was denied. You can still choose a specific folder below."
        }
    }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            val stored = runCatching {
                // Local playback only needs read access; do not request or persist write access.
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
                uri.toString()
            }.getOrElse {
                errorMessage = "Nuvio could not keep access to that folder. Choose it again and allow access."
                null
            }
            if (stored != null && stored !in folders) {
                val updated = (folders + stored).distinct()
                saveTreeUris(context, updated)
                folders = updated
            }
        }
    }

    // Reuse already-granted Android media permission so opening Local Files immediately
    // loads real MediaStore results. If permission has not been granted, the explicit Scan
    // device action remains responsible for showing the system permission prompt.
    LaunchedEffect(Unit) {
        if (hasVideoLibraryPermission(context)) {
            deviceScanRequest += 1
        }
    }

    LaunchedEffect(deviceScanRequest) {
        if (deviceScanRequest == 0) return@LaunchedEffect
        isDeviceLoading = true
        errorMessage = null
        try {
            deviceFiles = withContext(Dispatchers.IO) { queryDeviceVideos(context) }
        } catch (security: SecurityException) {
            errorMessage = "Android denied access to the video library. Grant video access in app settings or choose a folder."
        } catch (error: Exception) {
            errorMessage = "Could not scan device videos: ${error.message ?: error::class.simpleName ?: "unknown error"}"
        } finally {
            isDeviceLoading = false
        }
    }

    LaunchedEffect(folders) {
        if (folders.isEmpty()) {
            folderFiles = emptyList()
            return@LaunchedEffect
        }
        isFolderLoading = true
        errorMessage = null
        try {
            val result = withContext(Dispatchers.IO) {
                val files = mutableListOf<LocalVideoFile>()
                val failedFolders = mutableSetOf<String>()
                folders.forEach { uriString ->
                    try {
                        files += scanFolderVideos(context, Uri.parse(uriString))
                    } catch (_: Exception) {
                        failedFolders += uriString
                    }
                }
                files to failedFolders
            }
            folderFiles = result.first
            if (result.second.isNotEmpty()) {
                errorMessage = "Some selected folders could not be read. Access may have expired; remove and select the folder again."
            }
        } catch (error: Exception) {
            errorMessage = "Could not scan selected folders: ${error.message ?: error::class.simpleName ?: "unknown error"}"
        } finally {
            isFolderLoading = false
        }
    }

    val files = remember(deviceFiles, folderFiles) {
        (deviceFiles + folderFiles).distinctBy { it.uri }.sortedBy { it.displayName.lowercase(Locale.ROOT) }
    }
    val matchedFile = remember(files, preferredMatchKey) {
        preferredMatchKey?.let { key -> files.firstOrNull { it.matchKey == key } }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
            }
            Column(Modifier.weight(1f)) {
                Text("Local files", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text("Play videos through Nuvio’s built-in player", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = {
                if (hasVideoLibraryPermission(context)) {
                    deviceScanRequest += 1
                } else {
                    permissionLauncher.launch(videoLibraryPermissions())
                }
            }) {
                Icon(Icons.Rounded.Refresh, contentDescription = "Refresh device library")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    if (hasVideoLibraryPermission(context)) {
                        deviceScanRequest += 1
                    } else {
                        permissionLauncher.launch(videoLibraryPermissions())
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Rounded.VideoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Scan device")
            }
            OutlinedButton(onClick = { folderPicker.launch(null) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Choose folder")
            }
        }

        if (folders.isNotEmpty()) {
            Text("Folder access", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            folders.forEach { uriString ->
                val label = remember(uriString) { folderLabel(Uri.parse(uriString)) }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(label, modifier = Modifier.weight(1f).padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    IconButton(onClick = {
                                runCatching {
                            context.contentResolver.releasePersistableUriPermission(
                                Uri.parse(uriString),
                                Intent.FLAG_GRANT_READ_URI_PERMISSION,
                            )
                        }
                        val updated = folders - uriString
                        saveTreeUris(context, updated)
                        folders = updated
                    }) {
                        Text("×", style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }

        if (errorMessage != null) {
            Text(errorMessage.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (preferredMatchKey != null) {
            Text(
                text = when {
                    matchedFile != null -> "WatchParty match found: ${matchedFile.displayName}"
                    isLoading -> "Looking for the shared file in your device library and granted folders…"
                    else -> "To join this WatchParty, scan your device library or grant access to the folder containing the same video. The video itself is not shared; only a filename-and-size match can be played in this party."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (matchedFile != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (isLoading) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Scanning videos…", style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("${files.size} videos found", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        if (files.isEmpty() && !isLoading) {
            Text("No videos loaded yet. Scan the device library or choose a folder to grant Nuvio access.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                items(files, key = { it.uri }) { file ->
                    val isMatch = preferredMatchKey != null && file.matchKey == preferredMatchKey
                    val canPlay = preferredMatchKey == null || isMatch
                    val cardModifier = Modifier.fillMaxWidth()
                        .then(if (canPlay) Modifier.clickable { onPlay(file) } else Modifier.alpha(0.55f))
                    Card(modifier = cardModifier) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(Icons.Rounded.VideoLibrary, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                            Column(Modifier.weight(1f)) {
                                Text(file.displayName, style = MaterialTheme.typography.bodyLarge, fontWeight = if (isMatch) FontWeight.SemiBold else FontWeight.Normal, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                val details = buildString {
                                    append(formatSize(file.sizeBytes))
                                    file.durationMs?.takeIf { it > 0L }?.let { append(" · ").append(formatDuration(it)) }
                                    file.folderName?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                                }
                                Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (isMatch) Text("WatchParty file match", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            }
                            Icon(Icons.Rounded.PlayArrow, contentDescription = "Play ${file.displayName}", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

private data class MediaTreeRow(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val modifiedAtMs: Long?,
    val folderName: String,
)

private fun hasVideoLibraryPermission(context: Context): Boolean {
    val mediaVideoGranted = Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_MEDIA_VIDEO,
        ) == PackageManager.PERMISSION_GRANTED
    val selectedVisualMediaGranted = Build.VERSION.SDK_INT >= 34 &&
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        ) == PackageManager.PERMISSION_GRANTED
    val legacyGranted = Build.VERSION.SDK_INT <= 32 &&
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
    return mediaVideoGranted || selectedVisualMediaGranted || legacyGranted
}

private fun videoLibraryPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    )
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

private fun queryDeviceVideos(context: Context): List<LocalVideoFile> {
    val columns = mutableListOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.SIZE,
        MediaStore.Video.Media.DURATION,
        MediaStore.Video.Media.DATE_MODIFIED,
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) columns += MediaStore.Video.Media.RELATIVE_PATH
    val output = mutableListOf<LocalVideoFile>()
    context.contentResolver.query(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        columns.toTypedArray(),
        null,
        null,
        "${MediaStore.Video.Media.DATE_ADDED} DESC",
    )?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
        val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
        val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
        val durationColumn = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
        val modifiedColumn = cursor.getColumnIndex(MediaStore.Video.Media.DATE_MODIFIED)
        val folderColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) cursor.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH) else -1
        while (cursor.moveToNext()) {
            val id = cursor.getLong(idColumn)
            val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
            output += LocalVideoFile(
                uri = uri.toString(),
                displayName = cursor.getString(nameColumn)?.takeIf { it.isNotBlank() } ?: "Video $id",
                sizeBytes = cursor.getLong(sizeColumn).coerceAtLeast(0L),
                durationMs = if (durationColumn >= 0 && !cursor.isNull(durationColumn)) cursor.getLong(durationColumn).coerceAtLeast(0L) else null,
                modifiedAtMs = if (modifiedColumn >= 0 && !cursor.isNull(modifiedColumn)) cursor.getLong(modifiedColumn) * 1000L else null,
                folderName = if (folderColumn >= 0) cursor.getString(folderColumn)?.trimEnd('/') else null,
            )
        }
    } ?: return emptyList()
    return output
}

private fun scanFolderVideos(context: Context, treeUri: Uri): List<LocalVideoFile> {
    val resolver = context.contentResolver
    val rootId = DocumentsContract.getTreeDocumentId(treeUri)
    val rootName = rootId.substringAfterLast(':').substringAfterLast('/').ifBlank { "Selected folder" }
    val stack = ArrayDeque<Pair<String, Int>>()
    stack.add(rootId to 0)
    val output = mutableListOf<LocalVideoFile>()
    while (stack.isNotEmpty() && output.size < MAX_TREE_FILES) {
        val (parentId, depth) = stack.removeLast()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        resolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (cursor.moveToNext() && output.size < MAX_TREE_FILES) {
                val id = cursor.getString(idColumn) ?: continue
                val name = cursor.getString(nameColumn)?.takeIf { it.isNotBlank() } ?: continue
                val mime = cursor.getString(mimeColumn).orEmpty()
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (depth < MAX_TREE_DEPTH) stack.add(id to depth + 1)
                    continue
                }
                if (!isVideoDocument(name, mime)) continue
                val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                output += LocalVideoFile(
                    uri = documentUri.toString(),
                    displayName = name,
                    sizeBytes = if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) cursor.getLong(sizeColumn).coerceAtLeast(0L) else 0L,
                    modifiedAtMs = if (modifiedColumn >= 0 && !cursor.isNull(modifiedColumn)) cursor.getLong(modifiedColumn) else null,
                    folderName = rootName,
                )
            }
        }
    }
    return output
}

private fun isVideoDocument(name: String, mimeType: String): Boolean {
    if (mimeType.startsWith("video/", ignoreCase = true)) return true
    val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)
    return extension in VIDEO_EXTENSIONS
}

private fun loadTreeUris(context: Context): List<String> =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getStringSet(FOLDERS_KEY, emptySet())
        ?.toList()
        .orEmpty()
        .sorted()

private fun saveTreeUris(context: Context, uris: List<String>) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putStringSet(FOLDERS_KEY, uris.toSet())
        .apply()
}

private fun folderLabel(uri: Uri): String = runCatching {
    DocumentsContract.getTreeDocumentId(uri).substringAfterLast(':').substringAfterLast('/').ifBlank { "Granted folder" }
}.getOrDefault("Granted folder")

private fun formatSize(sizeBytes: Long): String {
    if (sizeBytes <= 0L) return "Size unavailable"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = sizeBytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) { value /= 1024.0; unit++ }
    return "${"%.1f".format(Locale.ROOT, value)} ${units[unit]}"
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) "$hours:%02d:%02d".format(minutes, seconds) else "%d:%02d".format(minutes, seconds)
}
