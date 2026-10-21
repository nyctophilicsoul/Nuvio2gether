package com.nuvio.app.features.streams

import com.nuvio.app.features.player.PlayerSettingsUiState

object StreamAutoPlayPolicy {
    fun isEffectivelyEnabled(settings: PlayerSettingsUiState): Boolean {
        if (settings.streamReuseLastLinkEnabled) return true
        if (settings.streamAutoPlayReuseBingeGroup && settings.streamAutoPlayPreferBingeGroup) return true

        return when (settings.streamAutoPlayMode) {
            StreamAutoPlayMode.MANUAL -> false
            StreamAutoPlayMode.FIRST_STREAM, StreamAutoPlayMode.SEMI_AUTOMATIC -> true
            StreamAutoPlayMode.REGEX_MATCH -> isRegexSelectionConfigured(settings.streamAutoPlayRegex)
        }
    }

    /**
     * Applies the configured source mode and per-provider allow-lists without reordering streams.
     * Keep this shared so normal autoplay, quality-first selection and dead-link fallback agree.
     */
    fun filterEligibleSources(
        streams: List<StreamItem>,
        source: StreamAutoPlaySource,
        installedAddonNames: Set<String>,
        selectedAddons: Set<String>,
        selectedPlugins: Set<String>,
    ): List<StreamItem> {
        val sourceScopedStreams = when (source) {
            StreamAutoPlaySource.ALL_SOURCES -> streams
            StreamAutoPlaySource.INSTALLED_ADDONS_ONLY ->
                streams.filter { it.serverTarget != null || it.addonName in installedAddonNames }
            StreamAutoPlaySource.ENABLED_PLUGINS_ONLY ->
                streams.filter { it.addonName !in installedAddonNames }
        }

        return sourceScopedStreams.filter { stream ->
            when {
                stream.serverTarget != null -> true
                stream.addonName in installedAddonNames ->
                    selectedAddons.isEmpty() || stream.addonName in selectedAddons
                else ->
                    selectedPlugins.isEmpty() || stream.addonName in selectedPlugins
            }
        }
    }

    fun isRegexSelectionConfigured(regexPattern: String): Boolean {
        val pattern = regexPattern.trim()
        if (pattern.isEmpty() || !pattern.any { it.isLetterOrDigit() }) return false
        return runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.isSuccess
    }
}
