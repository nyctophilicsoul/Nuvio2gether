package com.nuvio.app.features.player

import android.content.Context
import android.util.Base64

/**
 * Route delay overrides are deliberately local to this device: headphones, speakers, and TV
 * receivers have different latency characteristics, so these values should not sync across devices.
 */
internal object AudioDelayRouteStore {
    private const val PREFS_NAME = "nuvio_audio_delay_routes"
    private const val KEY_PREFIX = "delay_"

    fun loadDelayMs(context: Context, routeKey: String): Int {
        val key = KEY_PREFIX + Base64.encodeToString(
            routeKey.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(key, 0)
            .coerceIn(-1_000, 1_000)
    }

    fun saveDelayMs(context: Context, routeKey: String, delayMs: Int) {
        val key = KEY_PREFIX + Base64.encodeToString(
            routeKey.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(key, delayMs.coerceIn(-1_000, 1_000))
            .apply()
    }
}
