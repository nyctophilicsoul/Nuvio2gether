package com.nuvio.app.features.player

/** True only when this platform can apply an Android display refresh-rate hint. */
expect fun isFrameRateMatchingSupported(): Boolean

/** Validation shared by the runtime adapter and unit tests. */
internal object FrameRateMatchingPolicy {
    fun validSourceFrameRate(frameRate: Float?): Float? =
        frameRate?.takeIf { it.isFinite() && it in 10f..120f }
}
