package com.nuvio.app.features.cloudstream

/**
 * Android Full intentionally does not execute downloaded .cs3 DEX.
 * Providers must be reviewed, compiled into Nuvio, and registered through
 * CloudStreamProviderRegistry. The original package remains a verified artifact only.
 */
internal actual object CloudStreamPlatformRuntime {
    actual val supportsAndroidDex: Boolean = false

    actual fun initialize(context: Any?) = Unit

    actual suspend fun provider(plugin: CloudStreamPluginItem): CloudStreamProvider? = null

    actual fun unload(pluginId: String) = Unit

    actual fun clear() = Unit
}
