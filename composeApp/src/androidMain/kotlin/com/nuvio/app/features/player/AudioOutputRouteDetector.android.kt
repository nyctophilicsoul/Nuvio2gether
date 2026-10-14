package com.nuvio.app.features.player

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import java.util.Locale

internal data class AudioOutputRoute(
    val key: String,
    val label: String,
    val isBluetooth: Boolean,
)

/**
 * Best-effort current media output detection. On Android 13+ use attribute routing;
 * older APIs fall back to connected output devices. Detection never controls playback
 * directly; the player listens to route changes and reselects audio in place.
 */
internal object AudioOutputRouteDetector {
    @SuppressLint("NewApi")
    fun detect(context: Context): AudioOutputRoute? {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        val routed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                val attributes = android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
                manager.getAudioDevicesForAttributes(attributes).firstOrNull()
            }.getOrNull()
        } else {
            null
        }
        val device = routed ?: manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { routeRank(it.type) < UNSUPPORTED_RANK }
            .minByOrNull { routeRank(it.type) }
            ?: return null
        return device.toRoute()
    }

    @SuppressLint("NewApi")
    private fun AudioDeviceInfo.toRoute(): AudioOutputRoute {
        val type = when (type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bluetooth-a2dp"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth-sco"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "bluetooth-le-headset"
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> "bluetooth-le-speaker"
            AudioDeviceInfo.TYPE_BLE_BROADCAST -> "bluetooth-le-broadcast"
            AudioDeviceInfo.TYPE_HDMI -> "hdmi"
            AudioDeviceInfo.TYPE_HDMI_ARC -> "hdmi-arc"
            AudioDeviceInfo.TYPE_HDMI_EARC -> "hdmi-earc"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "usb-headset"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "usb-device"
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> "usb-accessory"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired-headset"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired-headphones"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "built-in-speaker"
            else -> "device-$type"
        }
        val label = productName?.toString()?.trim().orEmpty().ifBlank { type }
        val stableName = label.lowercase(Locale.US)
            .replace(Regex("\\s+"), "_")
            .replace(Regex("[^a-z0-9._:-]"), "")
            .take(80)
        val bluetooth = this.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            this.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            this.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
            this.type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
            this.type == AudioDeviceInfo.TYPE_BLE_BROADCAST
        return AudioOutputRoute(
            key = "$type:$stableName",
            label = label,
            isBluetooth = bluetooth,
        )
    }

    @SuppressLint("NewApi")
    private fun routeRank(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> 0
        AudioDeviceInfo.TYPE_BLE_HEADSET -> 1
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> 2
        AudioDeviceInfo.TYPE_BLE_BROADCAST -> 3
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> 4
        AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC -> 10
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY -> 20
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> 30
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 40
        else -> UNSUPPORTED_RANK
    }

    private const val UNSUPPORTED_RANK = 100
}
