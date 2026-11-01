package com.nuvio.app.features.cloudstream

/**
 * CloudStream .cs3 files contain Android DEX, not portable provider scripts.
 * Only reviewed adapters compiled into Nuvio are executable. Downloaded DEX is
 * retained as an artifact and is never loaded by the app.
 */
object CloudStreamCompatibilityResolver {
    private val crossPlatformAdapters = mapOf(
        "https://raw.githubusercontent.com/Kraptor123/cs-kraptor/master/repo.json#KickTR" to "kick-tr-v1",
    )

    fun resolve(metadata: CloudStreamPluginMetadata): CloudStreamCompatibility {
        val adapterId = crossPlatformAdapters[metadata.id.value]
        return if (adapterId != null) {
            CloudStreamCompatibility(
                runtimeKind = CloudStreamRuntimeKind.PrecompiledCrossPlatformAdapter,
                platformSupport = CloudStreamPlatformSupport.AndroidAndIos,
                adapterId = adapterId,
                reason = "This provider has a reviewed cross-platform adapter compiled into Nuvio.",
            )
        } else {
            CloudStreamCompatibility(
                runtimeKind = CloudStreamRuntimeKind.UnsupportedAndroidDex,
                platformSupport = CloudStreamPlatformSupport.Unsupported,
                reason = "This standard .cs3 package contains Android DEX code. Nuvio does not execute downloaded plugin code; a reviewed built-in adapter is required.",
            )
        }
    }
}

fun sortCloudStreamEpisodes(episodes: List<CloudStreamEpisode>): List<CloudStreamEpisode> =
    episodes.sortedWith(
        compareBy<CloudStreamEpisode>(
            { it.season ?: Int.MAX_VALUE },
            { it.episode ?: Int.MAX_VALUE },
            { it.name.lowercase() },
        ),
    )
