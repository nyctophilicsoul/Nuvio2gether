package com.nuvio.app.features.settings

import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

internal actual object NuvioEnhancedBackupFileBridge {
    actual fun exportBackup(fileName: String, payload: String, onResult: (Result<String>) -> Unit) {
        SwingUtilities.invokeLater {
            runCatching {
                val chooser = JFileChooser().apply {
                    dialogTitle = "Export Nuvio IPTV backup"
                    selectedFile = File(fileName.ifBlank { "nuvio-backup.json" })
                    fileFilter = FileNameExtensionFilter("JSON files", "json")
                }
                check(chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) { "Backup export cancelled." }
                val file = chooser.selectedFile
                file.writeText(payload)
                file.absolutePath
            }.fold({ onResult(Result.success(it)) }, { onResult(Result.failure(it)) })
        }
    }

    actual fun importBackup(onResult: (Result<String>) -> Unit) {
        SwingUtilities.invokeLater {
            runCatching {
                val chooser = JFileChooser().apply {
                    dialogTitle = "Import Nuvio IPTV playlist or backup"
                    fileFilter = FileNameExtensionFilter("Playlist / JSON files", "m3u", "m3u8", "json", "txt")
                }
                check(chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) { "Backup import cancelled." }
                chooser.selectedFile.readText()
            }.fold({ onResult(Result.success(it)) }, { onResult(Result.failure(it)) })
        }
    }
}
