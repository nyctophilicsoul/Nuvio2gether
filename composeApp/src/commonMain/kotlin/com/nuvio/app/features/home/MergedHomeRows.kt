package com.nuvio.app.features.home

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val MAX_MERGED_HOME_ROW_SOURCES = 8
const val HOME_MERGED_ROW_ITEM_LIMIT = 72

/**
 * A virtual home row assembled from existing addon catalogs. Source catalogs remain independent.
 *
 * [order] controls this definition's order in the Merged Rows editor. The row's home-screen
 * order, visibility and pin placement are stored separately by HomeCatalogSettingsRepository.
 */
@Serializable
data class MergedHomeRowDefinition(
    val id: String,
    val title: String,
    @SerialName("source_keys")
    val sourceKeys: List<String>,
    @SerialName("display_mode")
    val displayMode: String = MergedHomeRowDisplayMode.UNIFIED_CAROUSEL.storageValue,
    val deduplicate: Boolean = true,
    val order: Int = 0,
) {
    fun resolvedDisplayMode(): MergedHomeRowDisplayMode =
        MergedHomeRowDisplayMode.fromStorage(displayMode)
}

data class ResolvedMergedHomeRowSource(
    val definition: HomeCatalogDefinition,
    val title: String,
)

data class ResolvedMergedHomeRow(
    val definition: MergedHomeRowDefinition,
    val sources: List<ResolvedMergedHomeRowSource>,
    val unavailableSourceCount: Int,
)

enum class MergedHomeRowDisplayMode(val storageValue: String) {
    UNIFIED_CAROUSEL("unified_carousel"),
    SOURCE_TABS("source_tabs");

    companion object {
        fun fromStorage(value: String?): MergedHomeRowDisplayMode =
            values().firstOrNull { it.storageValue == value } ?: UNIFIED_CAROUSEL
    }
}

/** Keep merged row keys in their own namespace so they cannot collide with addon or collection keys. */
fun mergedHomeRowKey(id: String): String = "merged_home_row:$id"

/**
 * Combines sources fairly by taking one item from each source in turn. Deduplication uses the
 * same stable type/id key as normal home rows, never titles, so same-named films remain distinct.
 */
internal fun mergeHomeRowItems(
    sourceItems: List<List<MetaPreview>>,
    deduplicate: Boolean,
    maxItems: Int = 72,
): List<MetaPreview> {
    if (maxItems <= 0 || sourceItems.isEmpty()) return emptyList()

    val result = mutableListOf<MetaPreview>()
    val seen = mutableSetOf<String>()
    var index = 0
    var hasMore = true

    while (hasMore && result.size < maxItems) {
        hasMore = false
        sourceItems.forEach { items ->
            if (result.size >= maxItems) return@forEach
            val item = items.getOrNull(index) ?: return@forEach
            hasMore = true
            if (!deduplicate || seen.add(item.stableKey())) {
                result += item
            }
        }
        index++
        if (index >= (sourceItems.maxOfOrNull { it.size } ?: 0)) break
    }

    return result
}
