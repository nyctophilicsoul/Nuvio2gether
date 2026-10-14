package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max

/**
 * Compact, engine-neutral playback diagnostics. It consumes the snapshot already emitted by
 * the mobile player, so it does not introduce TV-only UI or a second playback observer.
 */
@Composable
internal fun PlaybackDiagnosticsOverlay(
    snapshot: PlayerPlaybackSnapshot,
    modifier: Modifier = Modifier,
) {
    val bufferAheadMs = max(0L, snapshot.bufferedPositionMs - snapshot.positionMs)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = "PLAYBACK DIAGNOSTICS",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
        DiagnosticLine("engine", snapshot.videoCodec ?: "video pending")
        DiagnosticLine("audio", snapshot.audioCodec ?: "audio pending")
        DiagnosticLine("speed", "${"%.2f".format(snapshot.playbackSpeed)}×")
        DiagnosticLine("buffer", "${bufferAheadMs / 1000.0} s")
        DiagnosticLine(
            "resolution",
            if (snapshot.videoWidth > 0 && snapshot.videoHeight > 0) {
                "${snapshot.videoWidth}×${snapshot.videoHeight}"
            } else {
                "unknown"
            },
        )
        if (snapshot.videoBitrate > 0) {
            DiagnosticLine("video", "${snapshot.videoBitrate / 1000} kbps")
        }
        if (snapshot.audioBitrate > 0) {
            DiagnosticLine("audio", "${snapshot.audioBitrate / 1000} kbps")
        }
        if (snapshot.durationMs > 0L) {
            DiagnosticLine("position", "${snapshot.positionMs / 1000}s / ${snapshot.durationMs / 1000}s")
        }
    }
}

@Composable
private fun DiagnosticLine(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
