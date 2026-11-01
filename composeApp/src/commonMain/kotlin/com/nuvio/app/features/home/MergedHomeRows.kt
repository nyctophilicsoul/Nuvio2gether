package com.nuvio.app.features.home

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val MAX_MERGED_HOME_ROW_SOURCES = 8
const val HOME_MERGED_ROW_ITEM_LIMIT = 72

@Serializable
data class MergedHomeRowDefinition(
    val id: String,
    val title: String,
    @SerialName("source_keys")
    val sourceKeys: List<String>,
    @SerialName("display_mode")
    val displayMode: String = MergedHomeRowDisplayMode.SOURCE_TABS.storageValue,
    val deduplicate: Boolean = true,
    val order: Int = 0,
    @SerialName("content_type")
    val contentType: String = MergedHomeRowContentType.ALL.storageValue,
    @SerialName("blend_mode")
    val blendMode: String = MergedHomeRowBlendMode.INTERLEAVE.storageValue,
    @SerialName("sort_mode")
    val sortMode: String = MergedHomeRowSortMode.OFF.storageValue,
    @SerialName("hide_included_rows")
    val hideIncludedRows: Boolean = false,
    @SerialName("hide_titles_without_posters")
    val hideTitlesWithoutPosters: Boolean = false,
) {
    fun resolvedDisplayMode(): MergedHomeRowDisplayMode =
        MergedHomeRowDisplayMode.fromStorage(displayMode)

    fun resolvedContentType(): MergedHomeRowContentType =
        MergedHomeRowContentType.fromStorage(contentType)

    fun resolvedBlendMode(): MergedHomeRowBlendMode =
        MergedHomeRowBlendMode.fromStorage(blendMode)

    fun resolvedSortMode(): MergedHomeRowSortMode =
        MergedHomeRowSortMode.fromStorage(sortMode)
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
            entries.firstOrNull { it.storageValue == value } ?: SOURCE_TABS
    }
}

enum class MergedHomeRowContentType(val storageValue: String) {
    ALL("all"),
    MOVIES("movies"),
    SERIES("series");

    companion object {
        fun fromStorage(value: String?): MergedHomeRowContentType =
            entries.firstOrNull { it.storageValue == value } ?: ALL
    }
}

/**
 * INTERLEAVE is retained as the legacy/default mode for already-saved rows.
 * SMART_FRESHNESS_POPULARITY can be selected for new or existing rows.
 */
enum class MergedHomeRowBlendMode(val storageValue: String) {
    INTERLEAVE("interleave"),
    SMART_FRESHNESS_POPULARITY("smart_freshness_popularity");

    companion object {
        fun fromStorage(value: String?): MergedHomeRowBlendMode =
            entries.firstOrNull { it.storageValue == value } ?: INTERLEAVE
    }
}

enum class MergedHomeRowSortMode(val storageValue: String) {
    OFF("off"),
    POPULARITY("popularity"),
    TOP_RATED("top_rated"),
    NEWEST("newest"),
    OLDEST("oldest");

    companion object {
        fun fromStorage(value: String?): MergedHomeRowSortMode =
            entries.firstOrNull { it.storageValue == value } ?: OFF
    }
}

/** Keep merged row keys in their own namespace so they cannot collide with addon or collection keys. */
fun mergedHomeRowKey(id: String): String = "merged_home_row:$id"

internal fun MetaPreview.matchesMergedContentType(type: MergedHomeRowContentType): Boolean =
    when (type) {
        MergedHomeRowContentType.ALL -> true
        MergedHomeRowContentType.MOVIES -> this.type.isMovieContentType()
        MergedHomeRowContentType.SERIES -> this.type.isSeriesContentType()
    }

private fun String.isMovieContentType(): Boolean =
    lowercase() in setOf("movie", "movies", "film", "films")

private fun String.isSeriesContentType(): Boolean =
    lowercase() in setOf("series", "tv", "show", "shows", "episode", "episodes")

internal fun filterMergedHomeItems(
    items: List<MetaPreview>,
    contentType: MergedHomeRowContentType,
    hideTitlesWithoutPosters: Boolean = false,
): List<MetaPreview> = items.filter { item ->
    item.matchesMergedContentType(contentType) &&
        (!hideTitlesWithoutPosters || item.poster.hasUsablePosterUrl() || item.rawPosterUrl.hasUsablePosterUrl())
}

private fun String?.hasUsablePosterUrl(): Boolean =
    !isNullOrBlank() && (startsWith("https://", ignoreCase = true) || startsWith("http://", ignoreCase = true))

/**
 * Combine provider catalogs. Smart mode ranks unique titles using relative freshness and popularity
 * percentiles, with ratings as a lower-weight tie-breaker and a small provider-diversity penalty.
 * Release dates are compared as ISO-like strings so this remains multiplatform and timezone-safe.
 */
internal fun mergeHomeRowItems(
    sourceItems: List<List<MetaPreview>>,
    deduplicate: Boolean,
    maxItems: Int = 72,
    blendMode: MergedHomeRowBlendMode = MergedHomeRowBlendMode.INTERLEAVE,
    sortMode: MergedHomeRowSortMode = MergedHomeRowSortMode.OFF,
    contentType: MergedHomeRowContentType = MergedHomeRowContentType.ALL,
    hideTitlesWithoutPosters: Boolean = false,
): List<MetaPreview> {
    if (maxItems <= 0 || sourceItems.isEmpty()) return emptyList()

    val filteredSources = sourceItems.map { items ->
        filterMergedHomeItems(items, contentType, hideTitlesWithoutPosters)
    }
    val blended = when (blendMode) {
        MergedHomeRowBlendMode.INTERLEAVE ->
            interleaveMergedSources(filteredSources, deduplicate, maxItems)
        MergedHomeRowBlendMode.SMART_FRESHNESS_POPULARITY ->
            smartFreshnessPopularityMerge(filteredSources, deduplicate, maxItems)
    }
    return sortMergedItems(blended, sortMode).take(maxItems)
}

private fun interleaveMergedSources(
    sourceItems: List<List<MetaPreview>>,
    deduplicate: Boolean,
    maxItems: Int,
): List<MetaPreview> {
    val result = mutableListOf<MetaPreview>()
    val seen = mutableSetOf<String>()
    var index = 0
    while (result.size < maxItems && index < (sourceItems.maxOfOrNull { it.size } ?: 0)) {
        sourceItems.forEach { items ->
            if (result.size >= maxItems) return@forEach
            val item = items.getOrNull(index) ?: return@forEach
            if (!deduplicate || seen.add(item.stableKey())) result += item
        }
        index++
    }
    return result
}

private data class SmartCandidate(
    val item: MetaPreview,
    val sourceIndexes: Set<Int>,
    val originalOrder: Int,
)

private fun smartFreshnessPopularityMerge(
    sourceItems: List<List<MetaPreview>>,
    deduplicate: Boolean,
    maxItems: Int,
): List<MetaPreview> {
    val candidates = mutableListOf<SmartCandidate>()
    if (deduplicate) {
        val grouped = linkedMapOf<String, MutableList<Pair<Int, MetaPreview>>>()
        var originalOrder = 0
        sourceItems.forEachIndexed { sourceIndex, items ->
            items.forEach { item ->
                grouped.getOrPut(item.stableKey()) { mutableListOf() } += sourceIndex to item
                originalOrder++
            }
        }
        var groupOrder = 0
        grouped.values.forEach { occurrences ->
            val representative = occurrences.maxWithOrNull(
                compareBy<Pair<Int, MetaPreview>>(
                    { it.second.releaseDateKey().orEmpty() },
                    { it.second.popularity ?: Double.NEGATIVE_INFINITY },
                    { it.second.ratingValue() ?: Double.NEGATIVE_INFINITY },
                ),
            )?.second ?: return@forEach
            candidates += SmartCandidate(
                item = representative,
                sourceIndexes = occurrences.mapTo(linkedSetOf()) { it.first },
                originalOrder = groupOrder++,
            )
        }
    } else {
        var order = 0
        sourceItems.forEachIndexed { sourceIndex, items ->
            items.forEach { item ->
                candidates += SmartCandidate(item, setOf(sourceIndex), order++)
            }
        }
    }
    if (candidates.isEmpty()) return emptyList()

    val freshness = percentileStringScores(candidates.map { it.item.releaseDateKey() }, descending = true)
    val popularity = percentileScores(candidates.map { it.item.popularity }, descending = true)
    val ratings = percentileScores(candidates.map { it.item.ratingValue() }, descending = true)

    val ranked = candidates.mapIndexed { index, candidate ->
        // Provider consensus is a small bonus; freshness and popularity remain the main drivers.
        val consensus = if (sourceItems.isEmpty()) 0.0 else {
            (candidate.sourceIndexes.size - 1).coerceAtLeast(0).toDouble() /
                sourceItems.size.coerceAtLeast(1).toDouble()
        }
        val score = freshness[index] * 0.45 +
            popularity[index] * 0.45 +
            ratings[index] * 0.08 +
            consensus * 0.02
        candidate to score
    }

    val providerUsage = IntArray(sourceItems.size)
    val remaining = ranked.toMutableList()
    val result = ArrayList<MetaPreview>(minOf(maxItems, candidates.size))
    while (remaining.isNotEmpty() && result.size < maxItems) {
        var bestIndex = 0
        var bestAdjustedScore = Double.NEGATIVE_INFINITY
        remaining.forEachIndexed { index, (candidate, baseScore) ->
            val leastUsedProvider = candidate.sourceIndexes.minOfOrNull { providerUsage.getOrElse(it) { 0 } } ?: 0
            val diversityPenalty = (leastUsedProvider * 0.035).coerceAtMost(0.18)
            val adjusted = baseScore - diversityPenalty
            if (adjusted > bestAdjustedScore) {
                bestAdjustedScore = adjusted
                bestIndex = index
            }
        }
        val selected = remaining.removeAt(bestIndex).first
        val provider = selected.sourceIndexes.minByOrNull { providerUsage.getOrElse(it) { 0 } }
        if (provider != null && provider in providerUsage.indices) providerUsage[provider]++
        result += selected.item
    }
    return result
}

private fun percentileScores(values: List<Double?>, descending: Boolean): List<Double> {
    val known = values.mapIndexedNotNull { index, value -> value?.let { index to it } }
    if (known.isEmpty()) return List(values.size) { 0.5 }
    if (known.size == 1) {
        return List(values.size) { index -> if (values[index] == null) 0.15 else 1.0 }
    }
    val sorted = known.sortedWith(
        if (descending) compareByDescending<Pair<Int, Double>> { it.second }.thenBy { it.first }
        else compareBy<Pair<Int, Double>> { it.second }.thenBy { it.first },
    )
    val scores = MutableList(values.size) { 0.15 }
    sorted.forEachIndexed { rank, (index, _) ->
        scores[index] = 1.0 - rank.toDouble() / (sorted.size - 1).toDouble()
    }
    return scores
}

private fun percentileStringScores(values: List<String?>, descending: Boolean): List<Double> {
    val known = values.mapIndexedNotNull { index, value -> value?.let { index to it } }
    if (known.isEmpty()) return List(values.size) { 0.5 }
    if (known.size == 1) {
        return List(values.size) { index -> if (values[index] == null) 0.15 else 1.0 }
    }
    val sorted = if (descending) {
        known.sortedWith(compareByDescending<Pair<Int, String>> { it.second }.thenBy { it.first })
    } else {
        known.sortedWith(compareBy<Pair<Int, String>> { it.second }.thenBy { it.first })
    }
    val scores = MutableList(values.size) { 0.15 }
    sorted.forEachIndexed { rank, (index, _) ->
        scores[index] = 1.0 - rank.toDouble() / (sorted.size - 1).toDouble()
    }
    return scores
}

private fun MetaPreview.releaseDateKey(): String? {
    val value = rawReleaseDate?.takeIf { it.isNotBlank() } ?: releaseInfo?.takeIf { it.isNotBlank() }
    val date = value?.trim()?.take(10) ?: return null
    return date.takeIf { candidate ->
        candidate.length >= 4 && candidate.take(4).all(Char::isDigit)
    }
}

private fun MetaPreview.ratingValue(): Double? =
    imdbRating?.trim()?.substringBefore('/')?.toDoubleOrNull()?.takeIf { it in 0.0..10.0 }

private fun sortMergedItems(
    items: List<MetaPreview>,
    sortMode: MergedHomeRowSortMode,
): List<MetaPreview> {
    if (sortMode == MergedHomeRowSortMode.OFF) return items
    val indexed = items.withIndex()
    val comparator = when (sortMode) {
        MergedHomeRowSortMode.OFF -> compareBy<IndexedValue<MetaPreview>> { it.index }
        MergedHomeRowSortMode.POPULARITY ->
            compareBy<IndexedValue<MetaPreview>>({ it.value.popularity == null }, { -(it.value.popularity ?: 0.0) }, { it.index })
        MergedHomeRowSortMode.TOP_RATED ->
            compareBy<IndexedValue<MetaPreview>>({ it.value.ratingValue() == null }, { -(it.value.ratingValue() ?: 0.0) }, { it.index })
        MergedHomeRowSortMode.NEWEST ->
            compareBy<IndexedValue<MetaPreview>> { it.value.releaseDateKey() == null }
                .thenByDescending { it.value.releaseDateKey() }
                .thenBy { it.index }
        MergedHomeRowSortMode.OLDEST ->
            compareBy<IndexedValue<MetaPreview>>({ it.value.releaseDateKey() == null }, { it.value.releaseDateKey().orEmpty() }, { it.index })
    }
    return indexed.sortedWith(comparator).map { it.value }
}

/**
 * Source names are presentation labels, not identifiers. Strip provider IDs and type suffixes,
 * then keep a short, recognisable 2–3 word label.
 */
internal fun cleanMergedSourceTabTitle(rawTitle: String): String {
    val cleaned = rawTitle
        .replace(Regex("\\s*\\([^)]*(?:/|_list_|[0-9]{5,})[^)]*\\)", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("\\b(?:movie|movies|series|tv)/[A-Za-z0-9_.:-]+", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("[·•|]+"), " ")
        .replace(Regex("[-–—]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    if (cleaned.isBlank()) return "Unnamed Source"
    val words = cleaned.split(' ')
        .filter { it.isNotBlank() && it.lowercase() !in setOf("movie", "movies", "series", "tv", "all", "catalog", "catalogs") }
    if (words.isEmpty()) return "Unnamed Source"
    val shortWords = when {
        words.size <= 3 -> words
        words.last().equals("next", ignoreCase = true) -> listOf(words.first(), words.last())
        else -> words.take(3)
    }
    return if (shortWords.size == 1) "${shortWords.first()} Feed" else shortWords.joinToString(" ")
}
