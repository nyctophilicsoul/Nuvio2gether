package com.nuvio.app.features.streams

/**
 * Quality-first source ordering for the redesigned autoplay flow.
 *
 * The selected quality is a ceiling: exact matches are tried first, then progressively
 * lower qualities. Sources of the same quality retain their provider order until the
 * playback coordinator has measured readiness/latency. This avoids pretending that a
 * URL is healthy merely because a provider returned it.
 */
enum class AutoPlayTargetQuality(val height: Int) {
    UHD_4K(2160),
    QHD_1440(1440),
    FULL_HD_1080(1080),
    HD_720(720),
    SD_480(480),
    SD_360(360),
}

data class AutoPlaySourceCandidate<T>(
    val sourceId: String,
    val value: T,
    val qualityHeight: Int?,
    val providerOrder: Int = 0,
)

object AutoPlaySelectionFramework {
    private val qualityMarkers = listOf(
        Regex("""(?i)(?:\b4320p?\b|\b8k\b)""") to 4320,
        Regex("""(?i)(?:\b2160p?\b|\b4k\b|\buhd\b)""") to 2160,
        Regex("""(?i)\b1440p?\b""") to 1440,
        Regex("""(?i)(?:\b1080p?\b|\bfhd\b|\bfull\s*hd\b)""") to 1080,
        Regex("""(?i)(?:\b720p?\b|\bhd\b)""") to 720,
        Regex("""(?i)\b480p?\b""") to 480,
        Regex("""(?i)\b360p?\b""") to 360,
    )

    /**
     * Sort provider groups by an explicit user-defined priority order.
     * Providers omitted from the priority list remain after configured providers and
     * keep their incoming order. Direct debrid entries retain their special first tier.
     */
    fun <T> orderProvidersByPriority(
        providers: List<T>,
        providerId: (T) -> String,
        priorityOrder: List<String>,
        isDirectDebrid: (T) -> Boolean = { false },
    ): List<T> {
        if (providers.size < 2 || priorityOrder.isEmpty()) return providers
        val ranks = priorityOrder.withIndex().associate { it.value to it.index }
        return providers.withIndex().sortedWith(
            compareBy<IndexedValue<T>> { if (isDirectDebrid(it.value)) 0 else 1 }
                .thenBy { ranks[providerId(it.value)]?.plus(1) ?: Int.MAX_VALUE }
                .thenBy { it.index },
        ).map { it.value }
    }

    /** Recalculates quality counts from the current stream snapshot, so late addon results update the picker. */
    fun countAvailableQualities(
        candidates: List<AutoPlaySourceCandidate<*>>,
    ): AutoPlayQualityCounts {
        var qhd1440 = 0
        var fullHd1080 = 0
        var hd720 = 0
        var lower = 0
        candidates.forEach { candidate ->
            when (candidate.qualityHeight) {
                4320, 2160, 1440 -> qhd1440++
                1080 -> fullHd1080++
                720 -> hd720++
                null, in 1..719 -> lower++
                else -> Unit
            }
        }
        return AutoPlayQualityCounts(qhd1440, fullHd1080, hd720, lower)
    }

    /** Detects quality from provider-supplied metadata only; it never guesses from URL size. */
    fun detectQualityHeight(metadata: String): Int? =
        qualityMarkers.firstOrNull { (regex, _) -> regex.containsMatchIn(metadata) }?.second

    /**
     * Orders sources in quality tiers. A requested quality falls back only to lower
     * tiers; unknown-quality sources are last and are never allowed to outrank a
     * known matching/lower-quality source. Provider order is stable within a tier.
     */
    fun <T> orderCandidates(
        candidates: List<AutoPlaySourceCandidate<T>>,
        target: AutoPlayTargetQuality,
    ): List<AutoPlaySourceCandidate<T>> {
        if (candidates.size < 2) {
            return candidates.filter { it.qualityHeight == null || it.qualityHeight <= target.height }
        }
        val targetHeight = target.height
        return candidates
            .filter { it.qualityHeight == null || it.qualityHeight <= targetHeight }
            .withIndex()
            .sortedWith(
                compareBy<IndexedValue<AutoPlaySourceCandidate<T>>> { indexed ->
                    val height = indexed.value.qualityHeight
                    when {
                        height == targetHeight -> 0
                        height != null && height < targetHeight -> 1
                        height == null -> 2
                        else -> 3
                    }
                }.thenBy { indexed ->
                    val height = indexed.value.qualityHeight
                    when {
                        height == null -> Int.MAX_VALUE
                        height >= targetHeight -> height - targetHeight
                        else -> targetHeight - height
                    }
                }.thenBy { indexed -> indexed.value.providerOrder }
                    .thenBy { indexed -> indexed.index },
            )
            .map { it.value }
    }

    /**
     * Ranks already-probed sources by quality first, then by measured startup latency
     * within that quality tier. Missing/failed probes sort after successful probes.
     * A caller must probe sources and pass only latency measurements it actually observed.
     */
    fun <T> rankByQualityThenLatency(
        candidates: List<AutoPlaySourceCandidate<T>>,
        target: AutoPlayTargetQuality,
        latencyMillisBySourceId: Map<String, Long>,
    ): List<AutoPlaySourceCandidate<T>> =
        orderCandidates(candidates, target)
            .groupBy { it.qualityHeight }
            .values
            .flatMap { tier ->
                tier.sortedWith(
                    compareBy<AutoPlaySourceCandidate<T>> {
                        latencyMillisBySourceId[it.sourceId] ?: Long.MAX_VALUE
                    }.thenBy { it.providerOrder },
                )
            }

    /** Converts current stream metadata into the common candidate model. */
    fun candidatesFor(
        streams: List<StreamItem>,
        target: AutoPlayTargetQuality,
    ): List<AutoPlaySourceCandidate<StreamItem>> {
        val candidates = streams.mapIndexed { index, stream ->
            val metadata = listOfNotNull(
                stream.name,
                stream.title,
                stream.description,
                stream.behaviorHints.filename,
                stream.clientResolve?.stream?.raw?.parsed?.rawTitle,
                stream.clientResolve?.stream?.raw?.parsed?.resolution,
                stream.clientResolve?.stream?.raw?.parsed?.quality,
            ).joinToString(" ")
            AutoPlaySourceCandidate(
                sourceId = "${stream.addonId}:${stream.url ?: stream.infoHash ?: index}",
                value = stream,
                qualityHeight = detectQualityHeight(metadata),
                providerOrder = index,
            )
        }
        return orderCandidates(candidates, target)
    }
}
