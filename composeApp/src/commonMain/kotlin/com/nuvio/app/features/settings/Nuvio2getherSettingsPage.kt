package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.nuvio.app.features.plugins.PluginRepository
import com.nuvio.app.features.streams.StreamAutoPlayMode
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
    LaunchedEffect(Unit) { PluginRepository.initialize() }
    val pluginUiState by PluginRepository.uiState.collectAsStateWithLifecycle()
    var showModePicker by remember { mutableStateOf(false) }
    var showAddonPicker by remember { mutableStateOf(false) }
    var showAdvancedSettings by remember { mutableStateOf(false) }
    var selectedFavouriteAddons by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showQualityPicker by remember { mutableStateOf(false) }
    var timeoutIndex by remember(settings.streamAutoPlayFallbackTimeoutSeconds) {
        mutableFloatStateOf(fallbackTimeoutOptions.indexOf(settings.streamAutoPlayFallbackTimeoutSeconds).coerceAtLeast(0).toFloat())
    }
    val addonNames = remember(addonUiState.addons) {
        addonUiState.addons.enabledAddons()
            .filter { addon -> addon.manifest?.resources?.any { it.name == "stream" } == true }
            .map { addon -> addon.displayTitle.ifBlank { addon.manifest?.name.orEmpty() } }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    }
    val addonPriority = remember(settings.streamAutoPlayAddonPriority, addonNames) {
        (settings.streamAutoPlayAddonPriority.filter { it in addonNames } + addonNames.filterNot { it in settings.streamAutoPlayAddonPriority })
    }
    val pluginNames = remember(pluginUiState.scrapers, pluginUiState.repositories, pluginUiState.groupStreamsByRepository) {
        if (pluginUiState.groupStreamsByRepository) {
            pluginUiState.repositories.map { it.name }.distinct().sorted()
        } else {
            pluginUiState.scrapers.filter { it.enabled }.map { it.name }.distinct().sorted()
        }
    }
    val pluginPriority = remember(settings.streamAutoPlayPluginPriority, pluginNames) {
        (settings.streamAutoPlayPluginPriority.filter { it in pluginNames } + pluginNames.filterNot { it in settings.streamAutoPlayPluginPriority })
    }

    LaunchedEffect(addonPriority) {
        if (addonNames.isNotEmpty() && settings.streamAutoPlayAddonPriority != addonPriority) {
            PlayerSettingsRepository.setStreamAutoPlayAddonPriority(addonPriority)
        }
    }
    LaunchedEffect(pluginPriority) {
        if (pluginNames.isNotEmpty() && settings.streamAutoPlayPluginPriority != pluginPriority) {
            PlayerSettingsRepository.setStreamAutoPlayPluginPriority(pluginPriority)
        }
    }

    val qualityLabel: @Composable (StreamAutoPlayPreferredQuality) -> String = { quality ->
        when (quality) {
            StreamAutoPlayPreferredQuality.ANY -> "Best available"
            StreamAutoPlayPreferredQuality.P2160 -> "2160p (4K)"
            StreamAutoPlayPreferredQuality.P1440 -> "1440p (2K)"
            StreamAutoPlayPreferredQuality.P1080 -> "1080p"
            StreamAutoPlayPreferredQuality.P720 -> "720p"
            StreamAutoPlayPreferredQuality.P480 -> "480p"
            StreamAutoPlayPreferredQuality.P360 -> "360p"
        }
    }

    val providerNames = remember(addonNames, pluginNames) { (addonNames + pluginNames).distinct().sorted() }

    SettingsSection(title = "Nuvio2gether", isTablet = isTablet) {
        SettingsGroup(isTablet = isTablet) {
            SettingsNavigationRow(
                title = "Stream selection mode",
                description = when (settings.streamAutoPlayMode) {
                    StreamAutoPlayMode.MANUAL -> "Manual — choose a stream"
                    StreamAutoPlayMode.SEMI_AUTOMATIC -> "Semi-Automatic — choose quality, then auto-select"
                    else -> "Automatic — select the best eligible stream"
                },
                isTablet = isTablet,
                onClick = { showModePicker = true },
            )
            if (settings.streamAutoPlayMode != StreamAutoPlayMode.MANUAL) {
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = "Advanced stream selection",
                    description = "Provider order, quality and format preferences, and fallback behaviour",
                    isTablet = isTablet,
                    onClick = { showAdvancedSettings = true },
                )
            }
        }
    }

    if (showAdvancedSettings) {
        AlertDialog(
            onDismissRequest = { showAdvancedSettings = false },
            title = { Text("Advanced stream selection") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Advanced ranking", style = MaterialTheme.typography.bodyLarge)
                            Text("Apply custom provider ordering and format preferences.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = settings.streamAutoPlayAdvancedRulesEnabled, onCheckedChange = PlayerSettingsRepository::setStreamAutoPlayAdvancedRulesEnabled)
                    }
                    SettingsNavigationRow(
                        title = "Favourite providers (up to 3)",
                        description = settings.streamAutoPlayFavoriteAddons.filter { it in providerNames }.joinToString().ifBlank { "Choose up to three preferred sources" },
                        isTablet = isTablet,
                        onClick = {
                            selectedFavouriteAddons = settings.streamAutoPlayFavoriteAddons.filter { it in providerNames }.toSet()
                            showAddonPicker = true
                        },
                    )
                    if (settings.streamAutoPlayMode == StreamAutoPlayMode.AUTOMATIC) {
                        SettingsGroupDivider(isTablet = isTablet)
                        SettingsNavigationRow(
                            title = "Preferred quality",
                            description = qualityLabel(settings.streamAutoPlayPreferredQuality),
                            isTablet = isTablet,
                            onClick = { showQualityPicker = true },
                        )
                    }
                    SettingsGroupDivider(isTablet = isTablet)
                    PriorityOrderSection(
                        title = "Add-on priority (first is preferred)",
                        entries = addonPriority,
                        enabled = settings.streamAutoPlayAdvancedRulesEnabled,
                        isTablet = isTablet,
                        onMove = { from, to ->
                            val updated = addonPriority.toMutableList()
                            val item = updated.removeAt(from)
                            updated.add(to, item)
                            PlayerSettingsRepository.setStreamAutoPlayAddonPriority(updated)
                        },
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    PriorityOrderSection(
                        title = "Plugin providers (always after add-ons)",
                        entries = pluginPriority,
                        enabled = settings.streamAutoPlayAdvancedRulesEnabled && pluginUiState.pluginsEnabled,
                        isTablet = isTablet,
                        onMove = { from, to ->
                            val updated = pluginPriority.toMutableList()
                            val item = updated.removeAt(from)
                            updated.add(to, item)
                            PlayerSettingsRepository.setStreamAutoPlayPluginPriority(updated)
                        },
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
                            enabled = settings.streamAutoPlayAdvancedRulesEnabled,
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showAdvancedSettings = false }) { Text("Done") } },
        )
    }

    if (showModePicker) {
        AlertDialog(
            onDismissRequest = { showModePicker = false },
            title = { Text("Stream selection mode") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(
                        onClick = {
                            PlayerSettingsRepository.setStreamAutoPlayMode(StreamAutoPlayMode.AUTOMATIC)
                            PlayerSettingsRepository.setStreamAutoPlaySmartSelectionEnabled(true)
                            showModePicker = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text("Automatic")
                            Text("Choose and play the best eligible stream automatically.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    TextButton(
                        onClick = {
                            PlayerSettingsRepository.setStreamAutoPlayMode(StreamAutoPlayMode.SEMI_AUTOMATIC)
                            PlayerSettingsRepository.setStreamAutoPlaySmartSelectionEnabled(true)
                            showModePicker = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text("Semi-Automatic")
                            Text("Choose a quality; Nuvio2gether selects a matching stream and can fall back.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    TextButton(
                        onClick = {
                            PlayerSettingsRepository.setStreamAutoPlayMode(StreamAutoPlayMode.MANUAL)
                            PlayerSettingsRepository.setStreamAutoPlaySmartSelectionEnabled(false)
                            showModePicker = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text("Manual")
                            Text("Always show the source list and let me choose.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showModePicker = false }) { Text("Done") } },
        )
    }

    if (showAddonPicker) {
        AlertDialog(
            onDismissRequest = { showAddonPicker = false },
            title = { Text("Choose up to three favourite providers") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        "Selected providers are prioritised first. Add-ons and plugin providers can both be selected.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Selected: ${selectedFavouriteAddons.size.coerceAtMost(3)}/3",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    providerNames.forEach { addon ->
                        val selected = addon in selectedFavouriteAddons
                        TextButton(
                            onClick = {
                                selectedFavouriteAddons = when {
                                    selected -> selectedFavouriteAddons - addon
                                    selectedFavouriteAddons.size < 3 -> selectedFavouriteAddons + addon
                                    else -> selectedFavouriteAddons
                                }
                            },
                            enabled = selected || selectedFavouriteAddons.size < 3,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("${if (selected) "✓" else "○"}  $addon")
                        }
                    }
                    if (providerNames.isEmpty()) Text("No enabled add-ons or plugin providers are available.")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val favourites = selectedFavouriteAddons.filter { it in providerNames }.take(3)
                        PlayerSettingsRepository.setStreamAutoPlayFavoriteAddons(favourites.toSet())
                        PlayerSettingsRepository.setStreamAutoPlayPreferredAddon(favourites.firstOrNull { it in addonNames }.orEmpty())
                        showAddonPicker = false
                    },
                ) { Text("Save favourites") }
            },
            dismissButton = { TextButton(onClick = { showAddonPicker = false }) { Text("Cancel") } },
        )
    }
    if (showQualityPicker) {
        AlertDialog(
            onDismissRequest = { showQualityPicker = false },
            title = { Text(stringResource(Res.string.settings_nuvio2gether_preferred_quality)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    StreamAutoPlayPreferredQuality.entries.filter { it != StreamAutoPlayPreferredQuality.P360 }.forEach { quality ->
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

@Composable
private fun PriorityOrderSection(
    title: String,
    entries: List<String>,
    enabled: Boolean,
    isTablet: Boolean,
    onMove: (Int, Int) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (entries.isEmpty()) {
            Text("No enabled sources available.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            entries.forEachIndexed { index, entry ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${index + 1}. $entry", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { onMove(index, index - 1) }, enabled = enabled && index > 0) { Text("↑") }
                    TextButton(onClick = { onMove(index, index + 1) }, enabled = enabled && index < entries.lastIndex) { Text("↓") }
                }
            }
        }
    }
}

