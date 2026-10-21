package com.nuvio.app.features.home

import androidx.compose.ui.text.intl.Locale
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.collection.Collection
import com.nuvio.app.features.collection.CollectionRepository
import com.nuvio.app.features.servers.ServerCatalog
import com.nuvio.app.features.servers.ServerRepository
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

data class HomeCatalogSettingsItem(
    val key: String,
    val defaultTitle: String,
    val addonName: String,
    val customTitle: String = "",
    val enabled: Boolean = true,
    val heroSourceEnabled: Boolean = true,
    val order: Int = 0,
    val isCollection: Boolean = false,
    val collectionId: String? = null,
    val isPinnedToTop: Boolean = false,
    val addonId: String? = null,
    val addonLayoutMode: HomeAddonLayoutMode = HomeAddonLayoutMode.NORMAL,
    val rowPlacement: HomeCatalogRowPlacement = HomeCatalogRowPlacement.NORMAL,
) {
    val displayTitle: String
        get() = customTitle.ifBlank { defaultTitle }
}

enum class HomeCatalogRowPlacement(val storageValue: String) {
    NORMAL("normal"),
    PINNED_TOP("pinned_top"),
    PINNED_BOTTOM("pinned_bottom");

    companion object {
        fun fromStorage(value: String?): HomeCatalogRowPlacement =
            values().firstOrNull { it.storageValue == value } ?: NORMAL
    }
}

enum class HomeAddonLayoutMode(val storageValue: String) {
    NORMAL("normal"),
    LOCKED("locked"),
    PINNED_TOP("pinned_top"),
    PINNED_BOTTOM("pinned_bottom");

    companion object {
        fun fromStorage(value: String?): HomeAddonLayoutMode =
            values().firstOrNull { it.storageValue == value } ?: NORMAL
    }
}

data class HomeAddonLayoutItem(
    val addonId: String,
    val addonName: String,
    val catalogCount: Int,
    val mode: HomeAddonLayoutMode,
    val order: Int,
    val anchorOrder: Int,
)

internal data class HomeAddonLayoutPreference(
    val mode: HomeAddonLayoutMode,
    val order: Int,
    val anchorOrder: Int,
)

data class HomeCatalogSettingsUiState(
    val heroEnabled: Boolean = true,
    val showCatalogType: Boolean = true,
    val hideUnreleasedContent: Boolean = false,
    val items: List<HomeCatalogSettingsItem> = emptyList(),
    val addonGroups: List<HomeAddonLayoutItem> = emptyList(),
) {
    val signature: String
        get() = buildString {
            append(heroEnabled)
            append('|')
            append(showCatalogType)
            append('|')
            append(hideUnreleasedContent)
            append('|')
            append(
                items.joinToString(separator = "|") { item ->
                    "${item.key}:${item.order}:${item.enabled}:${item.heroSourceEnabled}:${item.customTitle}"
                }
            )
            append('|')
            append(addonGroups.joinToString(separator = "|") { group ->
                "${group.addonId}:${group.mode}:${group.order}:${group.anchorOrder}"
            })
        }
}

internal data class HomeCatalogPreference(
    val customTitle: String,
    val enabled: Boolean,
    val heroSourceEnabled: Boolean,
    val order: Int,
    val rowPlacement: HomeCatalogRowPlacement = HomeCatalogRowPlacement.NORMAL,
)

internal data class HomeCatalogSettingsSnapshot(
    val heroEnabled: Boolean,
    val showCatalogType: Boolean,
    val hideUnreleasedContent: Boolean,
    val preferences: Map<String, HomeCatalogPreference>,
    val addonLayouts: Map<String, HomeAddonLayoutPreference> = emptyMap(),
)

@Serializable
private data class StoredHomeCatalogPreference(
    val key: String,
    val customTitle: String = "",
    val enabled: Boolean = true,
    val heroSourceEnabled: Boolean = true,
    val order: Int = 0,
    val rowPlacement: String = HomeCatalogRowPlacement.NORMAL.storageValue,
)

@Serializable
private data class StoredHomeAddonLayoutPreference(
    val addonId: String,
    val mode: String = HomeAddonLayoutMode.NORMAL.storageValue,
    val order: Int = 0,
    val anchorOrder: Int = 0,
)

@Serializable
private data class StoredHomeCatalogSettingsPayload(
    val heroEnabled: Boolean = true,
    val showCatalogType: Boolean = true,
    val hideUnreleasedContent: Boolean = false,
    val items: List<StoredHomeCatalogPreference> = emptyList(),
    val addonLayouts: List<StoredHomeAddonLayoutPreference> = emptyList(),
)

object HomeCatalogSettingsRepository {
    const val HERO_SOURCE_SELECTION_LIMIT = 2

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _uiState = MutableStateFlow(HomeCatalogSettingsUiState())
    val uiState: StateFlow<HomeCatalogSettingsUiState> = _uiState.asStateFlow()

    private var hasLoaded = false
    private var definitions: List<HomeCatalogDefinition> = emptyList()
    private var collectionDefinitions: List<CollectionCatalogDefinition> = emptyList()
    private var lastCatalogSync: CatalogSyncInput? = null
    private var lastCollectionSync: Pair<List<Collection>, String>? = null
    private val preferencesRef = atomic<Map<String, StoredHomeCatalogPreference>>(emptyMap())
    private var preferences: Map<String, StoredHomeCatalogPreference>
        get() = preferencesRef.value
        set(value) {
            preferencesRef.value = value
        }
    private var heroEnabled = true
    private var showCatalogType = true
    private var hideUnreleasedContent = false
    private var addonLayouts: Map<String, StoredHomeAddonLayoutPreference> = emptyMap()

    fun onProfileChanged() {
        hasLoaded = false
        preferences = emptyMap()
        heroEnabled = true
        showCatalogType = true
        hideUnreleasedContent = false
        addonLayouts = emptyMap()
        definitions = emptyList()
        collectionDefinitions = emptyList()
        lastCatalogSync = null
        lastCollectionSync = null
        _uiState.value = HomeCatalogSettingsUiState()
    }

    fun clearLocalState() {
        hasLoaded = false
        definitions = emptyList()
        collectionDefinitions = emptyList()
        lastCatalogSync = null
        lastCollectionSync = null
        preferences = emptyMap()
        heroEnabled = true
        showCatalogType = true
        hideUnreleasedContent = false
        addonLayouts = emptyMap()
        _uiState.value = HomeCatalogSettingsUiState()
    }

    fun syncCatalogs(addons: List<ManagedAddon>) {
        ensureLoaded()
        val collections = CollectionRepository.collections.value
        val syncInput = CatalogSyncInput(addons, collections, Locale.current.toLanguageTag(), ServerRepository.uiState.value.revision)
        if (lastCatalogSync == syncInput) return
        definitions = buildHomeCatalogDefinitions(addons)
        collectionDefinitions = buildCollectionDefinitions(collections)
        lastCatalogSync = syncInput
        lastCollectionSync = lastCollectionSync?.takeIf {
            it.first == collections && it.second == syncInput.locale
        }
        if (definitions.isEmpty() && collectionDefinitions.isEmpty()) {
            publish()
            return
        }
        normalizePreferences()
        enforcePinnedCollectionsAtTop()
        publish()
        persist()
    }

    fun syncCollections(collections: List<Collection>) {
        ensureLoaded()
        val syncInput = collections to Locale.current.toLanguageTag()
        if (lastCollectionSync == syncInput) return
        collectionDefinitions = buildCollectionDefinitions(collections)
        lastCatalogSync = lastCatalogSync?.takeIf {
            it.collections == collections && it.locale == syncInput.second
        }
        lastCollectionSync = syncInput
        normalizePreferences()
        enforcePinnedCollectionsAtTop()
        publish()
        persist()
        HomeRepository.applyCurrentSettings()
    }

    internal fun snapshot(): HomeCatalogSettingsSnapshot {
        ensureLoaded()
        return HomeCatalogSettingsSnapshot(
            heroEnabled = heroEnabled,
            showCatalogType = showCatalogType,
            hideUnreleasedContent = hideUnreleasedContent,
            preferences = preferences.mapValues { (_, value) ->
                HomeCatalogPreference(
                    customTitle = value.customTitle,
                    enabled = value.enabled,
                    heroSourceEnabled = value.heroSourceEnabled,
                    order = value.order,
                    rowPlacement = HomeCatalogRowPlacement.fromStorage(value.rowPlacement),
                )
            },
            addonLayouts = addonLayouts.mapValues { (_, value) ->
                HomeAddonLayoutPreference(
                    mode = HomeAddonLayoutMode.fromStorage(value.mode),
                    order = value.order,
                    anchorOrder = value.anchorOrder,
                )
            },
        )
    }

    fun setHeroEnabled(enabled: Boolean) {
        ensureLoaded()
        heroEnabled = enabled
        publish()
        persist()
        HomeRepository.applyCurrentSettings()
    }

    fun setShowCatalogType(enabled: Boolean) {
        ensureLoaded()
        if (showCatalogType == enabled) return
        showCatalogType = enabled
        publish()
        persist()
        HomeRepository.applyCurrentSettings()
        HomeCatalogSettingsSyncService.triggerPush()
    }

    fun setHideUnreleasedContent(enabled: Boolean) {
        ensureLoaded()
        if (hideUnreleasedContent == enabled) return
        hideUnreleasedContent = enabled
        publish()
        persist()
        HomeRepository.applyCurrentSettings()
        HomeCatalogSettingsSyncService.triggerPush()
    }

    fun setHeroSourceEnabled(key: String, enabled: Boolean) {
        updatePreference(key, pushRemote = false) { preference ->
            if (!enabled) {
                preference.copy(heroSourceEnabled = false)
            } else if (selectedHeroSourceCount(excludingKey = key) >= HERO_SOURCE_SELECTION_LIMIT) {
                preference
            } else {
                preference.copy(heroSourceEnabled = true)
            }
        }
    }

    fun setRowPlacement(key: String, placement: HomeCatalogRowPlacement) {
        updatePreference(key) { preference ->
            preference.copy(rowPlacement = placement.storageValue)
        }
    }

    fun setEnabled(key: String, enabled: Boolean) {
        updatePreference(key) { preference ->
            preference.copy(enabled = enabled)
        }
    }

    fun setCustomTitle(key: String, title: String) {
        updatePreference(key) { preference ->
            preference.copy(customTitle = title)
        }
    }

    fun setAddonLayoutMode(addonId: String, mode: HomeAddonLayoutMode) {
        ensureLoaded()
        if (addonId.isBlank()) return
        val addonDefinitions = definitions.filter { definition ->
            !ServerCatalog.isServerKey(definition.key) && definition.addonGroupId() == addonId
        }
        if (addonDefinitions.isEmpty()) return
        val existing = addonLayouts[addonId]
        if (HomeAddonLayoutMode.fromStorage(existing?.mode) == mode && existing != null) return

        val firstOrder = existing?.order ?: _uiState.value.addonGroups
            .firstOrNull { it.addonId == addonId }?.order ?: 0
        val firstRowOrder = addonDefinitions.minOfOrNull { definition ->
            preferences[definition.key]?.order ?: Int.MAX_VALUE
        }?.takeIf { it != Int.MAX_VALUE } ?: firstOrder
        val anchorOrder = if (
            mode == HomeAddonLayoutMode.LOCKED &&
            HomeAddonLayoutMode.fromStorage(existing?.mode) != HomeAddonLayoutMode.LOCKED
        ) firstRowOrder else existing?.anchorOrder ?: firstRowOrder

        // Keep the add-on's group order even when its pin/lock mode is NORMAL.
        addonLayouts = addonLayouts + (
            addonId to StoredHomeAddonLayoutPreference(
                addonId = addonId,
                mode = mode.storageValue,
                order = firstOrder,
                anchorOrder = anchorOrder,
            )
        )
        publish()
        persist()
        HomeRepository.applyCurrentSettings()
        HomeCatalogSettingsSyncService.triggerPush()
    }

    fun moveAddonGroup(fromIndex: Int, toIndex: Int) {
        ensureLoaded()
        val groups = _uiState.value.addonGroups
        if (fromIndex !in groups.indices || toIndex !in groups.indices || fromIndex == toIndex) return

        val reordered = groups.toMutableList().apply {
            add(toIndex, removeAt(fromIndex))
        }
        val currentLayouts = addonLayouts.toMutableMap()
        reordered.forEachIndexed { index, group ->
            val existing = currentLayouts[group.addonId]
            currentLayouts[group.addonId] = StoredHomeAddonLayoutPreference(
                addonId = group.addonId,
                mode = existing?.mode ?: HomeAddonLayoutMode.NORMAL.storageValue,
                order = index,
                anchorOrder = if (
                    HomeAddonLayoutMode.fromStorage(existing?.mode) == HomeAddonLayoutMode.LOCKED
                ) index else existing?.anchorOrder ?: index,
            )
        }
        addonLayouts = currentLayouts
        publish()
        persist()
        HomeRepository.applyCurrentSettings()
        HomeCatalogSettingsSyncService.triggerPush()
    }

    fun resetToDefaults() {
        ensureLoaded()
        heroEnabled = true
        showCatalogType = true
        hideUnreleasedContent = false
        preferences = emptyMap()
        addonLayouts = emptyMap()
        normalizePreferences()
        publish()
        persist()
        HomeRepository.applyCurrentSettings()
        HomeCatalogSettingsSyncService.triggerPush()
    }

    fun moveUp(key: String) {
        move(key = key, direction = -1)
    }

    fun moveDown(key: String) {
        move(key = key, direction = 1)
    }

    fun moveByIndex(fromIndex: Int, toIndex: Int) {
        ensureLoaded()
        val allKeys = _uiState.value.items.map { it.key }
        if (allKeys.isEmpty()) return
        if (fromIndex !in allKeys.indices || toIndex !in allKeys.indices) return
        if (fromIndex == toIndex) return
        val orderedKeys = allKeys.toMutableList()
        orderedKeys.add(toIndex, orderedKeys.removeAt(fromIndex))
        val updatedPreferences = preferences.toMutableMap()
        orderedKeys.forEachIndexed { index, itemKey ->
            val current = updatedPreferences[itemKey] ?: return@forEachIndexed
            updatedPreferences[itemKey] = current.copy(order = index)
        }
        preferences = updatedPreferences
        publish()
        persist()
        HomeRepository.applyCurrentSettings()
        HomeCatalogSettingsSyncService.triggerPush()
    }

    private fun ensureLoaded() {
        if (hasLoaded) return
        hasLoaded = true

        val payload = HomeCatalogSettingsStorage.loadPayload().orEmpty().trim()
        if (payload.isEmpty()) return

        val parsedPayload = runCatching {
            json.decodeFromString<StoredHomeCatalogSettingsPayload>(payload)
        }.getOrNull()

        if (parsedPayload != null) {
            heroEnabled = parsedPayload.heroEnabled
            showCatalogType = parsedPayload.showCatalogType
            hideUnreleasedContent = parsedPayload.hideUnreleasedContent
            preferences = parsedPayload.items.associateBy { it.key }
            addonLayouts = parsedPayload.addonLayouts.associateBy { it.addonId }
            publish()
            return
        }

        val legacyItems = runCatching {
            json.decodeFromString<List<StoredHomeCatalogPreference>>(payload)
        }.getOrDefault(emptyList())

        preferences = legacyItems.associateBy { it.key }
        addonLayouts = emptyMap()
        publish()
    }

    private fun normalizePreferences() {
        val current = preferences
        data class UnifiedEntry(val key: String, val isCollection: Boolean)
        val catalogEntries = definitions.map { UnifiedEntry(it.key, false) }
        val collectionEntries = collectionDefinitions.map { UnifiedEntry(it.key, true) }
        val allEntries = catalogEntries + collectionEntries
        val knownKeys = allEntries.mapTo(linkedSetOf(), UnifiedEntry::key)
        var nextOrder = (current.values.maxOfOrNull(StoredHomeCatalogPreference::order) ?: -1) + 1

        val orderedEntries = allEntries.mapIndexed { defaultIndex, entry ->
            Triple(
                entry,
                current[entry.key]?.order ?: (nextOrder + defaultIndex),
                defaultIndex,
            )
        }.sortedWith(
            compareBy<Triple<UnifiedEntry, Int, Int>>(
                { it.second },
                { it.third },
            ),
        ).map { it.first }

        val normalized = current
            .filterKeys { it !in knownKeys }
            .toMutableMap()
        var enabledHeroSourceCount = 0
        orderedEntries.forEach { entry ->
            val stored = current[entry.key]
            val heroSourceEnabled = if (entry.isCollection) {
                false
            } else {
                (stored?.heroSourceEnabled ?: true) &&
                    enabledHeroSourceCount < HERO_SOURCE_SELECTION_LIMIT
            }
            if (heroSourceEnabled) {
                enabledHeroSourceCount += 1
            }
            normalized[entry.key] = StoredHomeCatalogPreference(
                key = entry.key,
                customTitle = stored?.customTitle.orEmpty(),
                enabled = stored?.enabled ?: true,
                heroSourceEnabled = heroSourceEnabled,
                order = stored?.order ?: nextOrder++,
            )
        }
        preferences = normalized.toMap()
    }

    private fun publish() {
        val collectionMap = collectionDefinitions.associateBy { it.key }
        val catalogItems = definitions
            .map { definition ->
                val preference = preferences[definition.key]
                HomeCatalogSettingsItem(
                    key = definition.key,
                    defaultTitle = definition.defaultTitle,
                    addonName = definition.addonName,
                    customTitle = preference?.customTitle.orEmpty(),
                    enabled = preference?.enabled ?: true,
                    heroSourceEnabled = preference?.heroSourceEnabled ?: true,
                    order = preference?.order ?: 0,
                    addonId = if (ServerCatalog.isServerKey(definition.key)) null else definition.addonGroupId(),
                    rowPlacement = HomeCatalogRowPlacement.fromStorage(preference?.rowPlacement),
                    addonLayoutMode = if (ServerCatalog.isServerKey(definition.key)) {
                        HomeAddonLayoutMode.NORMAL
                    } else {
                        HomeAddonLayoutMode.fromStorage(addonLayouts[definition.addonGroupId()]?.mode)
                    },
                )
            }

        val collectionItems = collectionDefinitions.map { colDef ->
            val preference = preferences[colDef.key]
            HomeCatalogSettingsItem(
                key = colDef.key,
                defaultTitle = colDef.title,
                addonName = colDef.subtitle,
                customTitle = preference?.customTitle.orEmpty(),
                enabled = preference?.enabled ?: true,
                heroSourceEnabled = false,
                order = preference?.order ?: 0,
                isCollection = true,
                collectionId = colDef.collectionId,
                isPinnedToTop = colDef.isPinnedToTop,
                rowPlacement = HomeCatalogRowPlacement.fromStorage(preference?.rowPlacement),
            )
        }

        val items = orderHomeCatalogSettingsItems(
            items = catalogItems + collectionItems,
            addonIdByCatalogKey = definitions
                .filterNot { ServerCatalog.isServerKey(it.key) }
                .associate { definition -> definition.key to definition.addonGroupId() },
            addonLayouts = addonLayouts.mapValues { (_, stored) ->
                HomeAddonLayoutPreference(
                    mode = HomeAddonLayoutMode.fromStorage(stored.mode),
                    order = stored.order,
                    anchorOrder = stored.anchorOrder,
                )
            },
        )
        val addonGroups = definitions
            .filterNot { ServerCatalog.isServerKey(it.key) }
            .groupBy { it.addonGroupId() }
            .map { (addonId, addonDefinitions) ->
                val firstOrder = addonDefinitions.minOfOrNull { definition ->
                    preferences[definition.key]?.order ?: Int.MAX_VALUE
                }?.takeIf { it != Int.MAX_VALUE } ?: 0
                val storedLayout = addonLayouts[addonId]
                HomeAddonLayoutItem(
                    addonId = addonId,
                    addonName = addonDefinitions.first().addonName,
                    catalogCount = addonDefinitions.size,
                    mode = HomeAddonLayoutMode.fromStorage(storedLayout?.mode),
                    order = storedLayout?.order ?: firstOrder,
                    anchorOrder = storedLayout?.anchorOrder ?: firstOrder,
                )
            }
            .sortedWith(compareBy<HomeAddonLayoutItem> { it.order }.thenBy { it.addonName.lowercase() })

        _uiState.value = HomeCatalogSettingsUiState(
            heroEnabled = heroEnabled,
            showCatalogType = showCatalogType,
            hideUnreleasedContent = hideUnreleasedContent,
            items = items,
            addonGroups = addonGroups,
        )
    }

    private fun persist() {
        HomeCatalogSettingsStorage.savePayload(
            json.encodeToString(
                StoredHomeCatalogSettingsPayload(
                    heroEnabled = heroEnabled,
                    showCatalogType = showCatalogType,
                    hideUnreleasedContent = hideUnreleasedContent,
                    items = preferences.values.sortedBy { it.order },
                    addonLayouts = addonLayouts.values.sortedWith(
                        compareBy<StoredHomeAddonLayoutPreference> { it.order }.thenBy { it.addonId },
                    ),
                ),
            ),
        )
    }

    private fun updatePreference(
        key: String,
        pushRemote: Boolean = true,
        transform: (StoredHomeCatalogPreference) -> StoredHomeCatalogPreference,
    ) {
        ensureLoaded()
        val current = preferences[key] ?: defaultPreferenceForMissingKey(key) ?: return
        val updated = transform(current)
        if (updated == current) return
        preferences = preferences + (key to updated)
        publish()
        persist()
        HomeRepository.applyCurrentSettings()
        if (pushRemote) {
            HomeCatalogSettingsSyncService.triggerPush()
        }
    }

    private fun selectedHeroSourceCount(excludingKey: String? = null): Int {
        val catalogKeys = definitions.mapTo(mutableSetOf()) { it.key }
        return preferences.count { (itemKey, preference) ->
            itemKey != excludingKey && itemKey in catalogKeys && preference.heroSourceEnabled
        }
    }

    private fun move(
        key: String,
        direction: Int,
    ) {
        ensureLoaded()
        val orderedKeys = _uiState.value.items.map { it.key }.toMutableList()
        if (orderedKeys.isEmpty()) return

        val currentIndex = orderedKeys.indexOf(key)
        if (currentIndex == -1) return

        val targetIndex = currentIndex + direction
        if (targetIndex !in orderedKeys.indices) return

        val movingKey = orderedKeys.removeAt(currentIndex)
        orderedKeys.add(targetIndex, movingKey)

        val updatedPreferences = preferences.toMutableMap()
        orderedKeys.forEachIndexed { index, itemKey ->
            val current = updatedPreferences[itemKey] ?: return@forEachIndexed
            updatedPreferences[itemKey] = current.copy(order = index)
        }
        preferences = updatedPreferences

        publish()
        persist()
        HomeRepository.applyCurrentSettings()
        HomeCatalogSettingsSyncService.triggerPush()
    }

    fun exportToSyncPayload(): SyncHomeCatalogPayload {
        ensureLoaded()
        val catalogDefinitionsByKey = definitions.associateBy { it.key }
        val collectionDefinitionsByKey = collectionDefinitions.associateBy { it.key }
        val items = preferences.values.filterNot { ServerCatalog.isServerKey(it.key) }.sortedBy { it.order }.map { pref ->
            val catalogDefinition = catalogDefinitionsByKey[pref.key]
            val collectionDefinition = collectionDefinitionsByKey[pref.key]
            val isCollection = collectionDefinition != null || pref.key.startsWith("collection_")
            if (isCollection) {
                SyncCatalogItem(
                    addonId = "",
                    type = "",
                    catalogId = "",
                    enabled = pref.enabled,
                    order = pref.order,
                    customTitle = pref.customTitle,
                    isCollection = true,
                    collectionId = collectionDefinition?.collectionId ?: pref.key.removePrefix("collection_"),
                    key = pref.key,
                )
            } else {
                val legacyParts = pref.key.split(':', limit = 3)
                SyncCatalogItem(
                    addonId = catalogDefinition?.addonIdForSync() ?: legacyParts.getOrElse(0) { "" },
                    type = catalogDefinition?.type ?: legacyParts.getOrElse(1) { "" },
                    catalogId = catalogDefinition?.catalogId ?: legacyParts.getOrElse(2) { "" },
                    enabled = pref.enabled,
                    order = pref.order,
                    customTitle = pref.customTitle,
                    isCollection = false,
                    key = pref.key,
                )
            }
        }
        return SyncHomeCatalogPayload(
            showCatalogType = showCatalogType,
            hideUnreleasedContent = hideUnreleasedContent,
            items = items,
            addonLayouts = addonLayouts.values
                .sortedWith(compareBy<StoredHomeAddonLayoutPreference> { it.order }.thenBy { it.addonId })
                .map { stored ->
                    SyncAddonLayoutPreference(
                        addonId = stored.addonId,
                        mode = stored.mode,
                        order = stored.order,
                        anchorOrder = stored.anchorOrder,
                    )
                },
        )
    }

    fun applyFromRemote(payload: SyncHomeCatalogPayload) {
        ensureLoaded()
        showCatalogType = payload.showCatalogType
        hideUnreleasedContent = payload.hideUnreleasedContent
        payload.addonLayouts
            ?.takeIf { layouts -> layouts.isNotEmpty() || payload.items.isNotEmpty() }
            ?.let { remoteLayouts ->
                addonLayouts = remoteLayouts
                    .filter { it.addonId.isNotBlank() }
                    .associate { layout ->
                        layout.addonId to StoredHomeAddonLayoutPreference(
                            addonId = layout.addonId,
                            mode = HomeAddonLayoutMode.fromStorage(layout.mode).storageValue,
                            order = layout.order,
                            anchorOrder = layout.anchorOrder,
                        )
                    }
            }
        if (payload.items.isNotEmpty()) {
            val existingHeroState = preferences.mapValues { it.value.heroSourceEnabled }
            val remotePreferences = payload.items.associate { item ->
                val key = item.preferenceKey()
                key to StoredHomeCatalogPreference(
                    key = key,
                    customTitle = item.customTitle,
                    enabled = item.enabled,
                    heroSourceEnabled = existingHeroState[key] ?: true,
                    order = item.order,
                )
            }
            val remoteKeys = remotePreferences.keys
            val knownKeys = knownPreferenceKeys()
            val preservedPreferences = preferences.filterKeys { key ->
                key !in remoteKeys && (key in knownKeys || key.requiresExplicitSyncKey())
            }
            preferences = preservedPreferences + remotePreferences
            normalizePreferences()
        }
        hasLoaded = true
        publish()
        persist()
        HomeRepository.applyCurrentSettings()
    }

    private fun allOrderedKeys(): List<String> {
        val catalogKeys = definitions.map { it.key }
        val collectionKeys = collectionDefinitions.map { it.key }
        return (catalogKeys + collectionKeys)
            .sortedBy { key -> preferences[key]?.order ?: Int.MAX_VALUE }
    }

    private fun enforcePinnedCollectionsAtTop() {
        val orderedKeys = allOrderedKeys()
        if (orderedKeys.isEmpty()) return

        val pinnedCollectionKeys = collectionDefinitions
            .asSequence()
            .filter { it.isPinnedToTop }
            .map { it.key }
            .toSet()
        if (pinnedCollectionKeys.isEmpty()) return

        val pinnedKeys = orderedKeys.filter { it in pinnedCollectionKeys }
        if (pinnedKeys.isEmpty()) return

        val nonPinnedKeys = orderedKeys.filterNot { it in pinnedCollectionKeys }
        val reorderedKeys = pinnedKeys + nonPinnedKeys
        if (reorderedKeys == orderedKeys) return

        val updatedPreferences = preferences.toMutableMap()
        reorderedKeys.forEachIndexed { index, itemKey ->
            val current = updatedPreferences[itemKey] ?: return@forEachIndexed
            updatedPreferences[itemKey] = current.copy(order = index)
        }
        preferences = updatedPreferences

        val addonKeysById = definitions
            .filterNot { ServerCatalog.isServerKey(it.key) }
            .groupBy { it.addonGroupId() }
            .mapValues { (_, addonDefinitions) -> addonDefinitions.mapTo(mutableSetOf(), HomeCatalogDefinition::key) }
        addonLayouts = addonLayouts.mapValues { (addonId, layout) ->
            if (HomeAddonLayoutMode.fromStorage(layout.mode) != HomeAddonLayoutMode.LOCKED) {
                layout
            } else {
                val groupKeys = addonKeysById[addonId].orEmpty()
                val refreshedAnchor = reorderedKeys.indexOfFirst { it in groupKeys }
                if (refreshedAnchor >= 0) layout.copy(anchorOrder = refreshedAnchor) else layout
            }
        }
    }

    private fun defaultPreferenceForMissingKey(key: String): StoredHomeCatalogPreference? {
        val isCollection = collectionDefinitions.any { it.key == key }
        val isCatalog = definitions.any { it.key == key }
        if (!isCollection && !isCatalog) return null

        return StoredHomeCatalogPreference(
            key = key,
            enabled = true,
            heroSourceEnabled = isCatalog &&
                selectedHeroSourceCount(excludingKey = key) < HERO_SOURCE_SELECTION_LIMIT,
            order = _uiState.value.items.firstOrNull { it.key == key }?.order
                ?: ((preferences.values.maxOfOrNull { it.order } ?: -1) + 1),
        )
    }

    private fun knownPreferenceKeys(): Set<String> =
        definitions.mapTo(mutableSetOf()) { it.key }.also { keys ->
            keys.addAll(collectionDefinitions.map { it.key })
        }

    private fun HomeCatalogDefinition.addonIdForSync(): String {
        val suffix = ":$type:$catalogId"
        return key.removeSuffix(suffix)
    }

    private fun HomeCatalogDefinition.addonGroupId(): String = addonIdForSync()

    private fun SyncCatalogItem.preferenceKey(): String =
        key.ifBlank {
            if (isCollection) {
                "collection_$collectionId"
            } else {
                "$addonId:$type:$catalogId"
            }
        }

    private fun String.requiresExplicitSyncKey(): Boolean =
        !startsWith("collection_") && count { it == ':' } > 2
}

internal fun orderHomeCatalogSettingsItems(
    items: List<HomeCatalogSettingsItem>,
    addonIdByCatalogKey: Map<String, String>,
    addonLayouts: Map<String, HomeAddonLayoutPreference>,
): List<HomeCatalogSettingsItem> {
    data class OrderedUnit(
        val order: Int,
        val stableIndex: Int,
        val rows: List<HomeCatalogSettingsItem>,
    )

    val original = items.withIndex()
        .sortedWith(compareBy<IndexedValue<HomeCatalogSettingsItem>> { it.value.order }.thenBy { it.index })
    if (original.isEmpty()) return emptyList()
    val rankByKey = original.mapIndexed { rank, indexed -> indexed.value.key to rank }.toMap()
    val addonRows = original.mapNotNull { indexed ->
        if (indexed.value.isCollection) null
        else addonIdByCatalogKey[indexed.value.key]?.let { it to indexed.value }
    }.groupBy({ it.first }, { it.second })

    fun rowOrder(rows: List<HomeCatalogSettingsItem>): Int =
        rows.minOfOrNull { rankByKey[it.key] ?: Int.MAX_VALUE } ?: Int.MAX_VALUE

    fun sortedRows(rows: List<HomeCatalogSettingsItem>) = rows.sortedWith(
        compareBy<HomeCatalogSettingsItem> { it.order }.thenBy { rankByKey[it.key] ?: Int.MAX_VALUE },
    )

    val pinnedCollections = original.map { it.value }.filter { it.isCollection && it.isPinnedToTop }
    val pinnedCollectionKeys = pinnedCollections.mapTo(mutableSetOf(), HomeCatalogSettingsItem::key)
    val topGroups = addonRows.filter { (id, _) ->
        HomeAddonLayoutMode.fromStorage(addonLayouts[id]?.mode) == HomeAddonLayoutMode.PINNED_TOP
    }.entries.sortedWith(compareBy<Map.Entry<String, List<HomeCatalogSettingsItem>>> {
        addonLayouts[it.key]?.order ?: rowOrder(it.value)
    }.thenBy { it.key })
    val bottomGroups = addonRows.filter { (id, _) ->
        HomeAddonLayoutMode.fromStorage(addonLayouts[id]?.mode) == HomeAddonLayoutMode.PINNED_BOTTOM
    }.entries.sortedWith(compareBy<Map.Entry<String, List<HomeCatalogSettingsItem>>> {
        addonLayouts[it.key]?.order ?: rowOrder(it.value)
    }.thenBy { it.key })
    val reserved = (topGroups + bottomGroups).mapTo(mutableSetOf()) { it.key }
    val units = mutableListOf<OrderedUnit>()

    addonRows.forEach { (addonId, rows) ->
        if (addonId in reserved) return@forEach
        val mode = HomeAddonLayoutMode.fromStorage(addonLayouts[addonId]?.mode)
        val storedOrder = addonLayouts[addonId]?.order
        val order = when (mode) {
            HomeAddonLayoutMode.LOCKED -> addonLayouts[addonId]?.anchorOrder ?: storedOrder ?: rowOrder(rows)
            else -> storedOrder ?: rowOrder(rows)
        }
        units += OrderedUnit(order, rowOrder(rows), sortedRows(rows))
    }

    original.forEach { indexed ->
        val item = indexed.value
        if (item.key in pinnedCollectionKeys) return@forEach
        if (item.isCollection || item.key !in addonIdByCatalogKey) {
            units += OrderedUnit(item.order, rankByKey[item.key] ?: indexed.index, listOf(item))
        }
    }

    val orderedMiddle = units.sortedWith(
        compareBy<OrderedUnit> { it.order }.thenBy { it.stableIndex },
    ).flatMap { it.rows }
    return pinnedCollections +
        topGroups.flatMap { sortedRows(it.value) } +
        orderedMiddle +
        bottomGroups.flatMap { sortedRows(it.value) }
}

internal fun orderHomeCatalogDefinitions(
    definitions: List<HomeCatalogDefinition>,
    snapshot: HomeCatalogSettingsSnapshot,
): List<HomeCatalogDefinition> {
    data class OrderedUnit(
        val order: Int,
        val stableIndex: Int,
        val rows: List<HomeCatalogDefinition>,
    )

    val original = definitions.withIndex().sortedWith(
        compareBy<IndexedValue<HomeCatalogDefinition>> {
            snapshot.preferences[it.value.key]?.order ?: Int.MAX_VALUE
        }.thenBy { it.index },
    )
    if (original.isEmpty()) return emptyList()
    val rankByKey = original.mapIndexed { rank, indexed -> indexed.value.key to rank }.toMap()

    fun addonId(definition: HomeCatalogDefinition): String? {
        if (ServerCatalog.isServerKey(definition.key)) return null
        val suffix = ":" + definition.type + ":" + definition.catalogId
        return definition.key.removeSuffix(suffix)
    }

    val addonRows = original.mapNotNull { indexed ->
        addonId(indexed.value)?.let { it to indexed.value }
    }.groupBy({ it.first }, { it.second })

    fun rowOrder(rows: List<HomeCatalogDefinition>): Int =
        rows.minOfOrNull { rankByKey[it.key] ?: Int.MAX_VALUE } ?: Int.MAX_VALUE

    fun sortedRows(rows: List<HomeCatalogDefinition>) = rows.sortedWith(
        compareBy<HomeCatalogDefinition> { snapshot.preferences[it.key]?.order ?: Int.MAX_VALUE }
            .thenBy { rankByKey[it.key] ?: Int.MAX_VALUE },
    )

    val topGroups = addonRows.filter { (id, _) ->
        HomeAddonLayoutMode.fromStorage(snapshot.addonLayouts[id]?.mode) == HomeAddonLayoutMode.PINNED_TOP
    }.entries.sortedWith(compareBy<Map.Entry<String, List<HomeCatalogDefinition>>> {
        snapshot.addonLayouts[it.key]?.order ?: rowOrder(it.value)
    }.thenBy { it.key })
    val bottomGroups = addonRows.filter { (id, _) ->
        HomeAddonLayoutMode.fromStorage(snapshot.addonLayouts[id]?.mode) == HomeAddonLayoutMode.PINNED_BOTTOM
    }.entries.sortedWith(compareBy<Map.Entry<String, List<HomeCatalogDefinition>>> {
        snapshot.addonLayouts[it.key]?.order ?: rowOrder(it.value)
    }.thenBy { it.key })
    val reserved = (topGroups + bottomGroups).mapTo(mutableSetOf()) { it.key }
    val units = mutableListOf<OrderedUnit>()

    addonRows.forEach { (id, rows) ->
        if (id in reserved) return@forEach
        val layout = snapshot.addonLayouts[id]
        val mode = HomeAddonLayoutMode.fromStorage(layout?.mode)
        val order = if (mode == HomeAddonLayoutMode.LOCKED) {
            layout?.anchorOrder ?: layout?.order ?: rowOrder(rows)
        } else {
            layout?.order ?: rowOrder(rows)
        }
        units += OrderedUnit(order, rowOrder(rows), sortedRows(rows))
    }

    original.forEach { indexed ->
        if (addonId(indexed.value) == null) {
            units += OrderedUnit(
                snapshot.preferences[indexed.value.key]?.order ?: Int.MAX_VALUE,
                rankByKey[indexed.value.key] ?: indexed.index,
                listOf(indexed.value),
            )
        }
    }

    return topGroups.flatMap { sortedRows(it.value) } +
        units.sortedWith(compareBy<OrderedUnit> { it.order }.thenBy { it.stableIndex }).flatMap { it.rows } +
        bottomGroups.flatMap { sortedRows(it.value) }
}

internal data class CollectionCatalogDefinition(
    val key: String,
    val collectionId: String,
    val title: String,
    val subtitle: String,
    val isPinnedToTop: Boolean,
)

internal fun visibleCollectionsWithUniqueIds(collections: List<Collection>): List<Collection> =
    collections
        .filter { collection -> collection.folders.isNotEmpty() }
        .distinctBy(Collection::id)

internal fun buildCollectionDefinitions(collections: List<Collection>): List<CollectionCatalogDefinition> =
    visibleCollectionsWithUniqueIds(collections).map { collection ->
        CollectionCatalogDefinition(
            key = "collection_${collection.id}",
            collectionId = collection.id,
            title = collection.title,
            subtitle = runBlocking { getString(Res.string.collections_folder_count, collection.folders.size) },
            isPinnedToTop = collection.pinToTop,
        )
    }

private data class CatalogSyncInput(
    val addons: List<ManagedAddon>,
    val collections: List<Collection>,
    val locale: String,
    val serverRevision: Int,
)
