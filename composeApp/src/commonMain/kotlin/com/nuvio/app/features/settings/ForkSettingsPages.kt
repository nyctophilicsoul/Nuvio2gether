package com.nuvio.app.features.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.isIos
import com.nuvio.app.features.player.AndroidPlaybackEngine
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.PlayerSettingsUiState
import com.nuvio.app.features.watchparty.WatchPartyManager
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource
import nuvio.composeapp.generated.resources.Res

/**
 * Fork-specific settings stay separate from upstream Nuvio settings.
 * These pages currently expose existing, implemented features only; new
 * controls should be added here when their behavior is integrated and tested.
 */
internal fun LazyListScope.nuvio2getherSettingsContent(
    isTablet: Boolean,
    onPlaybackClick: () -> Unit,
) {
    item {
        SettingsSection(
            title = "Nuvio2gether features",
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsNavigationRow(
                    title = "WatchParty",
                    description = "Join a synchronized watch session",
                    icon = Icons.Rounded.Groups,
                    isTablet = isTablet,
                    onClick = WatchPartyManager::openJoinDialog,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = "Playback settings",
                    description = "Open existing playback controls used by this fork",
                    icon = Icons.Rounded.PlayArrow,
                    isTablet = isTablet,
                    onClick = onPlaybackClick,
                )
            }
        }
    }
}

internal fun LazyListScope.nuvioEnhancedSettingsContent(
    isTablet: Boolean,
    settings: PlayerSettingsUiState,
    onPlaybackClick: () -> Unit,
) {
    item {
        SettingsSection(
            title = "Nuvio Enhanced features",
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                if (!isIos) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_av_delay_enabled),
                    description = stringResource(Res.string.settings_playback_av_delay_enabled_description),
                    checked = settings.audioVideoDelayEnabled,
                    enabled = !settings.externalPlayerEnabled &&
                        settings.androidPlaybackEngine != AndroidPlaybackEngine.Libmpv,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setAudioVideoDelayEnabled,
                )
                if (settings.audioVideoDelayEnabled) {
                    SettingsGroupDivider(isTablet = isTablet)
                    AudioDelaySliderRow(
                        delayMs = settings.audioVideoDelayMs,
                        enabled = !settings.externalPlayerEnabled &&
                            settings.androidPlaybackEngine != AndroidPlaybackEngine.Libmpv,
                    )
                    if (settings.androidPlaybackEngine != AndroidPlaybackEngine.Libmpv &&
                        !settings.externalPlayerEnabled) {
                        SettingsGroupDivider(isTablet = isTablet)
                        SettingsSwitchRow(
                            title = stringResource(Res.string.settings_playback_remember_audio_delay_per_device),
                            description = stringResource(Res.string.settings_playback_remember_audio_delay_per_device_description),
                            checked = settings.rememberAudioDelayPerDeviceEnabled,
                            isTablet = isTablet,
                            onCheckedChange = PlayerSettingsRepository::setRememberAudioDelayPerDeviceEnabled,
                        )
                    }
                }
                }
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_show_diagnostics),
                    description = stringResource(Res.string.settings_playback_show_diagnostics_description),
                    checked = settings.showPlaybackDiagnostics,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setShowPlaybackDiagnostics,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = "Playback engine and decoding",
                    description = "Configure the existing engine, hardware decoding and video output options",
                    icon = Icons.Rounded.PlayArrow,
                    isTablet = isTablet,
                    onClick = onPlaybackClick,
                )
            }
        }
    }
}


@androidx.compose.runtime.Composable
private fun AudioDelaySliderRow(
    delayMs: Int,
    enabled: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.settings_playback_av_delay_ms),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(Res.string.settings_playback_av_delay_value, delayMs),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = delayMs.coerceIn(-500, 500).toFloat(),
            onValueChange = { value ->
                PlayerSettingsRepository.setAudioVideoDelayMs(
                    ((value / 25f).roundToInt() * 25).coerceIn(-500, 500),
                )
            },
            valueRange = -500f..500f,
            steps = 39,
            enabled = enabled,
        )
    }
}
