package com.nuvio.app.features.livetv

import java.io.File
import java.security.MessageDigest
import java.util.prefs.Preferences

actual object LiveTvStorage {
    private val prefs: Preferences = Preferences.userRoot().node("com/nuvio/app/live_tv")
    private val root: File = File(System.getProperty("user.home"), ".nuvio/live_tv")

    private fun key(name: String, profileId: Int = resolveLiveTvStorageProfileId()) = "${name}_$profileId"
    private fun localPlaylistFile(profileId: Int = resolveLiveTvStorageProfileId()) =
        File(root, "playlists/$profileId.m3u")
    private fun cacheFile(profileId: Int, url: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.encodeToByteArray())
        val name = digest.joinToString("") { "%02x".format(it) }
        return File(root, "epg/$profileId/$name.xmltvcache")
    }

    actual fun loadSourceType(): LiveTvSourceType =
        runCatching { LiveTvSourceType.valueOf(prefs.get(key("source_type"), LiveTvSourceType.M3u.name)) }
            .getOrDefault(LiveTvSourceType.M3u)

    actual fun saveSourceType(type: LiveTvSourceType) { prefs.put(key("source_type"), type.name) }
    actual fun loadSourceUrl(): String? = prefs.get(key("source_url"), null)
    actual fun saveSourceUrl(url: String) { prefs.put(key("source_url"), url) }

    actual fun loadLocalPlaylistData(): String? =
        localPlaylistFile().takeIf(File::isFile)?.takeIf { it.length() <= 16L * 1024 * 1024 }?.readText()

    actual fun saveLocalPlaylistData(data: String) {
        val file = localPlaylistFile()
        file.parentFile?.mkdirs()
        if (data.isBlank()) file.delete() else file.writeText(data)
    }

    actual fun loadStalkerSettings(): LiveTvStalkerSettings = LiveTvStalkerSettings(
        portalUrl = prefs.get(key("stalker_portal"), ""),
        macAddress = prefs.get(key("stalker_mac"), ""),
        username = prefs.get(key("stalker_username"), ""),
        password = prefs.get(key("stalker_password"), ""),
    )

    actual fun saveStalkerSettings(settings: LiveTvStalkerSettings) {
        prefs.put(key("stalker_portal"), settings.portalUrl)
        prefs.put(key("stalker_mac"), settings.macAddress)
        prefs.put(key("stalker_username"), settings.username)
        prefs.put(key("stalker_password"), settings.password)
    }

    actual fun loadXtreamSettings(): LiveTvXtreamSettings = LiveTvXtreamSettings(
        serverUrl = prefs.get(key("xtream_server"), ""),
        username = prefs.get(key("xtream_username"), ""),
        password = prefs.get(key("xtream_password"), ""),
    )

    actual fun saveXtreamSettings(settings: LiveTvXtreamSettings) {
        prefs.put(key("xtream_server"), settings.serverUrl)
        prefs.put(key("xtream_username"), settings.username)
        prefs.put(key("xtream_password"), settings.password)
    }

    actual fun loadFavoriteUrls(): Set<String> =
        prefs.get(key("favorite_urls"), "").lineSequence().filter(String::isNotBlank).toSet()

    actual fun saveFavoriteUrls(urls: Set<String>) {
        prefs.put(key("favorite_urls"), urls.joinToString("\n"))
    }

    actual fun loadRecentChannel(): LiveTvRecentChannel? {
        val url = prefs.get(key("recent_url"), null)?.takeIf(String::isNotBlank) ?: return null
        return LiveTvRecentChannel(
            streamUrl = url,
            name = prefs.get(key("recent_name"), ""),
            logoUrl = prefs.get(key("recent_logo"), null),
            group = prefs.get(key("recent_group"), ""),
            tvgId = prefs.get(key("recent_tvg_id"), null),
        )
    }

    actual fun saveRecentChannel(channel: LiveTvRecentChannel?) {
        val prefix = key("recent_url")
        if (channel == null) {
            listOf("recent_url", "recent_name", "recent_logo", "recent_group", "recent_tvg_id")
                .forEach { prefs.remove(key(it)) }
        } else {
            prefs.put(prefix, channel.streamUrl)
            prefs.put(key("recent_name"), channel.name)
            prefs.put(key("recent_logo"), channel.logoUrl ?: "")
            prefs.put(key("recent_group"), channel.group)
            prefs.put(key("recent_tvg_id"), channel.tvgId ?: "")
        }
    }

    private fun readCache(profileId: Int, url: String): LiveTvEpgCacheEntry? = runCatching {
        val file = cacheFile(profileId, url)
        if (!file.isFile || file.length() > 40L * 1024 * 1024) return null
        val savedAt = file.lastModified()
        LiveTvEpgCacheEntry(url = url, content = file.readText(), savedAtEpochMs = savedAt)
    }.getOrNull()

    private fun writeCache(profileId: Int, entry: LiveTvEpgCacheEntry) {
        runCatching {
            val file = cacheFile(profileId, entry.url)
            require(entry.content.encodeToByteArray().size <= 40 * 1024 * 1024)
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(entry.content)
            if (!temp.renameTo(file)) {
                file.writeText(entry.content)
                temp.delete()
            }
            file.setLastModified(entry.savedAtEpochMs)
        }
    }

    init {
        LiveTvEpgStorageBridge.readCache = ::readCache
        LiveTvEpgStorageBridge.writeCache = ::writeCache
    }
}
