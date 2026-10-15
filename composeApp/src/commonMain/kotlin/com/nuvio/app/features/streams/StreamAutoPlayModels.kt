package com.nuvio.app.features.streams

enum class StreamAutoPlayMode {
    MANUAL,
    AUTOMATIC,
    SEMI_AUTOMATIC,
    /** Legacy mode retained for saved settings compatibility. */
    FIRST_STREAM,
    /** Legacy mode retained for saved settings compatibility. */
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
