package com.nuvio.app.features.streams

/**
 * User-facing strategy for selecting a source when Play is pressed.
 * Kept separate from StreamAutoPlayMode, which continues to represent the
 * legacy first-stream/regex matching policy for backwards compatibility.
 */
enum class AutoPlayStrategy {
    SMART_AUTO,
    QUALITY_PICKER,
    MANUAL,
}

enum class StreamAutoPlayMode {
    MANUAL,
    FIRST_STREAM,
    REGEX_MATCH,
}

enum class StreamAutoPlaySource {
    ALL_SOURCES,
    INSTALLED_ADDONS_ONLY,
    ENABLED_PLUGINS_ONLY,
}

enum class StreamAutoPlayPreferredQuality(val targetHeight: Int?) {
    ANY(null), P2160(2160), P1440(1440), P1080(1080), P720(720), P480(480), P360(360),
}

/** A single addon/plugin priority entry; smaller priority values are tried first. */
data class AutoPlayProviderPriority(
    val providerId: String,
    val displayName: String,
    val priority: Int,
)

/** Counts are snapshots of fetched stream metadata and should be recomputed on every fetch update. */
data class AutoPlayQualityCounts(
    val qhd1440: Int = 0,
    val fullHd1080: Int = 0,
    val hd720: Int = 0,
    val lower: Int = 0,
) {
    fun countFor(quality: StreamAutoPlayPreferredQuality): Int = when (quality) {
        StreamAutoPlayPreferredQuality.P2160,
        StreamAutoPlayPreferredQuality.P1440 -> qhd1440
        StreamAutoPlayPreferredQuality.P1080 -> fullHd1080
        StreamAutoPlayPreferredQuality.P720 -> hd720
        StreamAutoPlayPreferredQuality.P480,
        StreamAutoPlayPreferredQuality.P360 -> lower
        StreamAutoPlayPreferredQuality.ANY -> qhd1440 + fullHd1080 + hd720 + lower
    }
}
