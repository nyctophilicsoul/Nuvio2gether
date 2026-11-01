package com.nuvio.app.features.cloudstream

/**
 * Android Full runtime currently exposes only reviewed, built-in adapters.
 * Arbitrary downloaded .cs3 DEX execution is deliberately disabled until the
 * CloudStream runtime API artifact can be included and audited in this fork.
 */
internal actual object CloudStreamPlatformRuntime {
    actual val supportsAndroidDex: Boolean = false

    actual fun initialize(context: Any?) = Unit

    actual suspend fun provider(plugin: CloudStreamPluginItem): CloudStreamProvider? =
        CloudStreamProviderRegistry.find(plugin.metadata.id.value)

    actual fun unload(pluginId: String) = Unit
    actual fun clear() = Unit
}
