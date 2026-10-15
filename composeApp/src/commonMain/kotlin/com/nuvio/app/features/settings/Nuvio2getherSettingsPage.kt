package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.streams.StreamAutoPlayPreferredQuality
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_nuvio2gether_addon_picker_title
import nuvio.composeapp.generated.resources.settings_nuvio2gether_fallback_timeout
import nuvio.composeapp.generated.resources.settings_nuvio2gether_fallback_timeout_description
import nuvio.composeapp.generated.resources.settings_nuvio2gether_favourite_addon
import nuvio.composeapp.generated.resources.settings_nuvio2gether_favourite_addon_none
import nuvio.composeapp.generated.resources.settings_nuvio2gether_preferred_quality
import nuvio.composeapp.generated.resources.settings_nuvio2gether_seconds
import nuvio.composeapp.generated.resources.settings_nuvio2gether_smart_selection
import nuvio.composeapp.generated.resources.settings_nuvio2gether_smart_selection_description
import org.jetbrains.compose.resources.stringResource

private val fallbackTimeoutOptions = listOf(5, 10, 15, 20, 30, 45, 60, 90, 120)

@Composable
internal fun nuvio2getherSettingsContent(isTablet: Boolean) {
    PlayerSettingsRepository.ensureLoaded()
    val settings by PlayerSettingsRepository.uiState.collectAsStateWithLifecycle()
    val addonUiState by AddonRepository.uiState.collectAsStateWithLifecycle()
    var showAddonPicker by remember { mutableStateOf(false) }
    var showQualityPicker by remember { mutableStateOf(false) }
    var timeoutIndex by remember(settings.streamAutoPlayFallbackTimeoutSeconds) {
        mutableFloatStateOf(fallbackTimeoutOptions.indexOf(settings.streamAutoPlayFallbackTimeoutSeconds).coerceAtLeast(0).toFloat())
    }
    val addonNames = remember(addonUiState.addons) {
        addonUiState.addons.enabledAddons()
            .mapNotNull { it.manifest }
            .filter { manifest -> manifest.resources.any { it.name == "stream" } }
            .map { it.name }
            .distinct()
            .sorted()
    }
    val qualityLabel: @Composable (StreamAutoPlayPreferredQuality) -> String = { quality ->
        when (quality) {
            StreamAutoPlayPreferredQuality.ANY -> "Best available"
            StreamAutoPlayPreferredQuality.P2160 -> "2160p (4K)"
            StreamAutoPlayPreferredQuality.P1440 -> "1440p"
            StreamAutoPlayPreferredQuality.P1080 -> "1080p"
            StreamAutoPlayPreferredQuality.P720 -> "720p"
            StreamAutoPlayPreferredQuality.P480 -> "480p"
            StreamAutoPlayPreferredQuality.P360 -> "360p"
        }
    }

    SettingsSection(title = "Nuvio2gether", isTablet = isTablet) {
        SettingsGroup(isTablet = isTablet) {
            SettingsSwitchRow(
                title = stringResource(Res.string.settings_nuvio2gether_smart_selection),
                description = stringResource(Res.string.settings_nuvio2gether_smart_selection_description),
                checked = settings.streamAutoPlaySmartSelectionEnabled,
                isTablet = isTablet,
                onCheckedChange = PlayerSettingsRepository::setStreamAutoPlaySmartSelectionEnabled,
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsNavigationRow(
                title = stringResource(Res.string.settings_nuvio2gether_favourite_addon),
                description = settings.streamAutoPlayPreferredAddon.ifBlank { stringResource(Res.string.settings_nuvio2gether_favourite_addon_none) },
                enabled = settings.streamAutoPlaySmartSelectionEnabled,
                isTablet = isTablet,
                onClick = { showAddonPicker = true },
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsNavigationRow(
                title = stringResource(Res.string.settings_nuvio2gether_preferred_quality),
                description = qualityLabel(settings.streamAutoPlayPreferredQuality),
                enabled = settings.streamAutoPlaySmartSelectionEnabled,
                isTablet = isTablet,
                onClick = { showQualityPicker = true },
            )
            SettingsGroupDivider(isTablet = isTablet)
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val seconds = fallbackTimeoutOptions[timeoutIndex.toInt().coerceIn(fallbackTimeoutOptions.indices)]
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(stringResource(Res.string.settings_nuvio2gether_fallback_timeout), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(Res.string.settings_nuvio2gether_fallback_timeout_description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(stringResource(Res.string.settings_nuvio2gether_seconds, seconds), color = MaterialTheme.colorScheme.primary)
                }
                Slider(
                    value = timeoutIndex,
                    onValueChange = { timeoutIndex = it },
                    onValueChangeFinished = {
                        PlayerSettingsRepository.setStreamAutoPlayFallbackTimeoutSeconds(fallbackTimeoutOptions[timeoutIndex.toInt().coerceIn(fallbackTimeoutOptions.indices)])
                    },
                    valueRange = 0f..fallbackTimeoutOptions.lastIndex.toFloat(),
                    steps = fallbackTimeoutOptions.size - 2,
                    enabled = settings.streamAutoPlaySmartSelectionEnabled,
                )
            }
        }
    }

    if (showAddonPicker) {
        AlertDialog(
            onDismissRequest = { showAddonPicker = false },
            title = { Text(stringResource(Res.string.settings_nuvio2gether_addon_picker_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(onClick = { PlayerSettingsRepository.setStreamAutoPlayPreferredAddon(""); showAddonPicker = false }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(Res.string.settings_nuvio2gether_favourite_addon_none))
                    }
                    addonNames.forEach { addon ->
                        TextButton(onClick = { PlayerSettingsRepository.setStreamAutoPlayPreferredAddon(addon); showAddonPicker = false }, modifier = Modifier.fillMaxWidth()) {
                            Text(addon)
                        }
                    }
                    if (addonNames.isEmpty()) Text("No enabled add-ons provide streams.")
                }
            },
            confirmButton = { TextButton(onClick = { showAddonPicker = false }) { Text("Done") } },
        )
    }
    if (showQualityPicker) {
        AlertDialog(
            onDismissRequest = { showQualityPicker = false },
            title = { Text(stringResource(Res.string.settings_nuvio2gether_preferred_quality)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    StreamAutoPlayPreferredQuality.entries.forEach { quality ->
                        TextButton(onClick = { PlayerSettingsRepository.setStreamAutoPlayPreferredQuality(quality); showQualityPicker = false }, modifier = Modifier.fillMaxWidth()) {
                            Text(qualityLabel(quality))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showQualityPicker = false }) { Text("Done") } },
        )
    }
}
