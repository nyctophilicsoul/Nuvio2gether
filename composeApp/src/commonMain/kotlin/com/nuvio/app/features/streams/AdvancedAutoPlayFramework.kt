package com.nuvio.app.features.streams

/**
 * Pure decision framework for the Auto play Advanced flow.
 *
 * Fetch timeout and playback startup timeout intentionally have separate values and
 * separate lifecycle entry points. The host UI/player owns timers and calls these
 * decisions when the corresponding deadline expires.
 */
data class AdvancedAutoPlaySettings(
    val addonFetchTimeoutSeconds: Int = DEFAULT_ADDON_FETCH_TIMEOUT_SECONDS,
    val playbackStartupTimeoutSeconds: Int = DEFAULT_PLAYBACK_STARTUP_TIMEOUT_SECONDS,
    val maxBufferEvents: Int = DEFAULT_MAX_BUFFER_EVENTS,
    val addonPriority: List<String> = emptyList(),
    val pluginPriority: List<String> = emptyList(),
) {
    init {
        require(addonFetchTimeoutSeconds >= 0) { "Add-on fetch timeout cannot be negative" }
        require(playbackStartupTimeoutSeconds >= 0) { "Playback startup timeout cannot be negative" }
        require(maxBufferEvents >= 0) { "Maximum buffer events cannot be negative" }
    }

    companion object {
        const val DEFAULT_ADDON_FETCH_TIMEOUT_SECONDS = 15
        const val DEFAULT_PLAYBACK_STARTUP_TIMEOUT_SECONDS = 10
        const val DEFAULT_MAX_BUFFER_EVENTS = 10
    }
}

enum class AdvancedStreamQuality(val height: Int?) {
    UHD_4K(2160),
    QHD_1440(1440),
    FHD_1080(1080),
    HD_720(720),
    SD_480(480),
    SD_360(360),
    UNKNOWN(null);

    companion object {
        fun fromMetadata(name: String?, description: String? = null, url: String? = null): AdvancedStreamQuality {
            val text = listOfNotNull(name, description, url).joinToString(" ").lowercase()
            return when {
                Regex("""(^|[^0-9])(?:2160p|4k|uhd)([^0-9]|$)""").containsMatchIn(text) -> UHD_4K
                Regex("""(^|[^0-9])1440p([^0-9]|$)""").containsMatchIn(text) -> QHD_1440
                Regex("""(^|[^0-9])(?:1080p|1080i|fhd)([^0-9]|$)""").containsMatchIn(text) -> FHD_1080
                Regex("""(^|[^0-9])(?:720p|hd)([^0-9]|$)""").containsMatchIn(text) -> HD_720
                Regex("""(^|[^0-9])(?:480p|480i|sd)([^0-9]|$)""").containsMatchIn(text) -> SD_480
                Regex("""(^|[^0-9])(?:360p|240p)([^0-9]|$)""").containsMatchIn(text) -> SD_360
                else -> UNKNOWN
            }
        }
    }
}

data class AdvancedStreamCandidate(
    val id: String,
    val addonId: String,
    val addonName: String,
    val isPlugin: Boolean,
    val quality: AdvancedStreamQuality,
    val originalIndex: Int,
)

/** A deterministic plan that never silently upgrades above the requested quality. */
object AdvancedStreamSelection {
    fun orderByProviderPriority(
        streams: List<AdvancedStreamCandidate>,
        settings: AdvancedAutoPlaySettings,
    ): List<AdvancedStreamCandidate> {
        fun rank(name: String, priority: List<String>): Int =
            priority.indexOfFirst { it.equals(name, ignoreCase = true) }
                .let { if (it < 0) Int.MAX_VALUE else it }

        return streams.sortedWith(
            compareBy<AdvancedStreamCandidate> { if (it.isPlugin) 1 else 0 }
                .thenBy { if (it.isPlugin) rank(it.addonName, settings.pluginPriority) else rank(it.addonName, settings.addonPriority) }
                .thenBy { it.originalIndex }
        )
    }

    fun qualityCounts(streams: List<AdvancedStreamCandidate>): Map<AdvancedStreamQuality, Int> =
        AdvancedStreamQuality.entries.associateWith { quality -> streams.count { it.quality == quality } }

    fun planForQuality(
        streams: List<AdvancedStreamCandidate>,
        selectedQuality: AdvancedStreamQuality,
        settings: AdvancedAutoPlaySettings,
    ): List<AdvancedStreamCandidate> {
        require(selectedQuality != AdvancedStreamQuality.UNKNOWN) { "Choose a known quality to enable quality fallback" }
        val ordered = orderByProviderPriority(streams, settings)
        val descending = AdvancedStreamQuality.entries
            .filter { it.height != null }
            .sortedByDescending { it.height!! }
        val requestedHeight = selectedQuality.height!!
        val allowedQualities = descending.filter { it.height!! <= requestedHeight }.toSet()
        return ordered.filter { it.quality in allowedQualities }
            .sortedWith(compareBy<AdvancedStreamCandidate> {
                // Exhaust the selected quality before falling back to lower quality.
                if (it.quality == selectedQuality) 0 else 1
            }.thenByDescending { it.quality.height ?: 0 }
                .thenBy { if (it.isPlugin) 1 else 0 }
                .thenBy { if (it.isPlugin) rank(it.addonName, settings.pluginPriority) else rank(it.addonName, settings.addonPriority) }
                .thenBy { it.originalIndex })
    }

    private fun rank(name: String, priority: List<String>): Int =
        priority.indexOfFirst { it.equals(name, ignoreCase = true) }
            .let { if (it < 0) Int.MAX_VALUE else it }
}

enum class AdvancedPlaybackOutcome { KEEP_PLAYING, RETRY_NEXT_STREAM, IGNORE_STALE_EVENT }

data class AdvancedPlaybackAttempt(
    val streamId: String,
    val started: Boolean = false,
    val bufferEvents: Int = 0,
    val failedStreamIds: Set<String> = emptySet(),
)

/**
 * Event-driven playback guard. Only call onBuffering after playback is attempted;
 * source-fetch progress must never be passed to this state machine.
 */
object AdvancedPlaybackGuard {
    fun onPlaybackStarted(attempt: AdvancedPlaybackAttempt): AdvancedPlaybackAttempt =
        attempt.copy(started = true)

    fun onBuffering(
        attempt: AdvancedPlaybackAttempt,
        settings: AdvancedAutoPlaySettings,
    ): Pair<AdvancedPlaybackAttempt, AdvancedPlaybackOutcome> {
        if (attempt.streamId in attempt.failedStreamIds) {
            return attempt to AdvancedPlaybackOutcome.IGNORE_STALE_EVENT
        }
        val nextCount = attempt.bufferEvents + 1
        val updated = attempt.copy(bufferEvents = nextCount)
        return if (!attempt.started &&
            settings.playbackStartupTimeoutSeconds == 0
        ) {
            updated.copy(failedStreamIds = updated.failedStreamIds + attempt.streamId) to
                AdvancedPlaybackOutcome.RETRY_NEXT_STREAM
        } else if (nextCount > settings.maxBufferEvents) {
            updated.copy(failedStreamIds = updated.failedStreamIds + attempt.streamId) to
                AdvancedPlaybackOutcome.RETRY_NEXT_STREAM
        } else {
            updated to AdvancedPlaybackOutcome.KEEP_PLAYING
        }
    }

    /** Called only when the player's startup deadline expires, not when providers time out. */
    fun onStartupDeadline(
        attempt: AdvancedPlaybackAttempt,
        settings: AdvancedAutoPlaySettings,
    ): Pair<AdvancedPlaybackAttempt, AdvancedPlaybackOutcome> {
        if (attempt.started || attempt.streamId in attempt.failedStreamIds ||
            settings.playbackStartupTimeoutSeconds == 0
        ) return attempt to AdvancedPlaybackOutcome.KEEP_PLAYING
        return attempt.copy(failedStreamIds = attempt.failedStreamIds + attempt.streamId) to
            AdvancedPlaybackOutcome.RETRY_NEXT_STREAM
    }
}
