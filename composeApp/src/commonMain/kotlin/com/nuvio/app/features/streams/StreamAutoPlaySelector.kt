package com.nuvio.app.features.streams

import com.nuvio.app.core.build.AppFeaturePolicy
import kotlin.math.abs

object StreamAutoPlaySelector {

    fun orderAddonStreams(
        groups: List<AddonStreamGroup>,
        installedOrder: List<String>,
        preferredGroupIds: Set<String> = emptySet(),
    ): List<AddonStreamGroup> {
        if (groups.isEmpty()) return groups
        if (preferredGroupIds.isNotEmpty()) {
            val (preferred, rest) = groups.partition { it.addonId in preferredGroupIds }
            return preferred + orderAddonStreams(rest, installedOrder)
        }

        val addonRankByName = HashMap<String, Int>(installedOrder.size)
        installedOrder.forEachIndexed { index, addonName ->
            if (addonName !in addonRankByName) {
                addonRankByName[addonName] = index
            }
        }

        val (directDebridEntries, remainingEntries) = groups.partition { group ->
            group.addonId.startsWith("debrid:") ||
                group.streams.any { stream -> stream.isAddonDebridCandidate && stream.isDirectDebridStream }
        }
        if (installedOrder.isEmpty()) return directDebridEntries + remainingEntries

        val (addonEntries, pluginEntries) = remainingEntries.partition { group ->
            group.addonName in addonRankByName
        }
        val orderedAddons = addonEntries.sortedBy { group ->
            addonRankByName.getValue(group.addonName)
        }
        return directDebridEntries + orderedAddons + pluginEntries
    }

    /**
     * Starts early selection when the top three configured add-ons have returned
     * more than [minimumCountExclusive] distinct playable streams for one known quality.
     * Unknown-quality links and non-playable/external links do not inflate the threshold.
     */
    fun hasEarlySelectionThreshold(
        groups: List<AddonStreamGroup>,
        preferredAddonOrder: List<String>,
        minimumCountExclusive: Int = 10,
    ): Boolean {
        val topThree = preferredAddonOrder.distinct().take(3).toSet()
        if (topThree.isEmpty()) return false

        val uniqueStreamsByQuality = mutableMapOf<Int, MutableSet<String>>()
        groups.asSequence()
            .filter { it.addonName in topThree }
            .flatMap { it.streams.asSequence() }
            .forEach { stream ->
                val height = stream.detectAutoPlayQualityHeight() ?: return@forEach
                val directUrl = stream.playableDirectUrl ?: return@forEach
                val normalizedUrl = directUrl.trim().substringBefore('#')
                if (normalizedUrl.isBlank()) return@forEach
                uniqueStreamsByQuality.getOrPut(height) { mutableSetOf() }.add(normalizedUrl)
            }

        return uniqueStreamsByQuality.values.any { it.size > minimumCountExclusive }
    }

    fun selectAutoPlayStream(
        streams: List<StreamItem>,
        mode: StreamAutoPlayMode,
        regexPattern: String,
        source: StreamAutoPlaySource,
        installedAddonNames: Set<String>,
        selectedAddons: Set<String>,
        selectedPlugins: Set<String>,
        preferredBingeGroup: String? = null,
        preferBingeGroupInSelection: Boolean = false,
        bingeGroupOnly: Boolean = false,
        debridEnabled: Boolean = true,
        activeResolverProviderId: String? = null,
        preferredAddonName: String = "",
        preferredQuality: StreamAutoPlayPreferredQuality = StreamAutoPlayPreferredQuality.ANY,
        preferredSourceOrder: List<String> = emptyList(),
    ): StreamItem? =
        evaluateAutoPlayStream(
            streams = streams,
            mode = mode,
            regexPattern = regexPattern,
            source = source,
            installedAddonNames = installedAddonNames,
            selectedAddons = selectedAddons,
            selectedPlugins = selectedPlugins,
            preferredBingeGroup = preferredBingeGroup,
            preferBingeGroupInSelection = preferBingeGroupInSelection,
            bingeGroupOnly = bingeGroupOnly,
            debridEnabled = debridEnabled,
            activeResolverProviderId = activeResolverProviderId,
            preferredAddonName = preferredAddonName,
            preferredQuality = preferredQuality,
            preferredSourceOrder = preferredSourceOrder,
        ).stream

    fun evaluateAutoPlayStream(
        streams: List<StreamItem>,
        mode: StreamAutoPlayMode,
        regexPattern: String,
        source: StreamAutoPlaySource,
        installedAddonNames: Set<String>,
        selectedAddons: Set<String>,
        selectedPlugins: Set<String>,
        preferredBingeGroup: String? = null,
        preferBingeGroupInSelection: Boolean = false,
        bingeGroupOnly: Boolean = false,
        debridEnabled: Boolean = true,
        activeResolverProviderId: String? = null,
        preferredAddonName: String = "",
        preferredQuality: StreamAutoPlayPreferredQuality = StreamAutoPlayPreferredQuality.ANY,
        preferredSourceOrder: List<String> = emptyList(),
    ): StreamAutoPlayEvaluation {
        if (streams.isEmpty()) return StreamAutoPlayEvaluation()

        val sourceScopedStreams = when (source) {
            StreamAutoPlaySource.ALL_SOURCES -> streams
            StreamAutoPlaySource.INSTALLED_ADDONS_ONLY -> streams.filter { it.serverTarget != null || it.addonName in installedAddonNames }
            StreamAutoPlaySource.ENABLED_PLUGINS_ONLY -> streams.filter { it.addonName !in installedAddonNames }
        }
        val candidateStreams = sourceScopedStreams.filter { stream ->
            val isAddonStream = stream.addonName in installedAddonNames
            if (stream.serverTarget != null) {
                true
            } else if (isAddonStream) {
                selectedAddons.isEmpty() || stream.addonName in selectedAddons
            } else {
                selectedPlugins.isEmpty() || stream.addonName in selectedPlugins
            }
        }
        if (candidateStreams.isEmpty()) return StreamAutoPlayEvaluation()
        if (mode == StreamAutoPlayMode.MANUAL && !bingeGroupOnly) {
            return StreamAutoPlayEvaluation()
        }

        val targetBingeGroup = preferredBingeGroup?.trim().orEmpty()
        val bingeGroupCandidates = if (preferBingeGroupInSelection && targetBingeGroup.isNotEmpty()) {
            candidateStreams.filter { stream -> stream.behaviorHints.bingeGroup == targetBingeGroup }
        } else {
            emptyList()
        }
        val orderedBingeGroupCandidates = prioritizeStreams(
            streams = bingeGroupCandidates,
            preferredAddonName = preferredAddonName,
            preferredQuality = preferredQuality,
            preferredSourceOrder = preferredSourceOrder,
        )
        val preferredReadyStream = orderedBingeGroupCandidates.firstOrNull { stream ->
            stream.isAutoPlayable(debridEnabled, activeResolverProviderId)
        }
        if (bingeGroupOnly) {
            val readyStreams = preferredReadyStream?.let(::listOf).orEmpty()
            return StreamAutoPlayEvaluation(
                stream = preferredReadyStream,
                readyStreams = readyStreams,
                hasPendingDebridCandidate = preferredReadyStream == null &&
                    orderedBingeGroupCandidates.any {
                        it.isPendingDebridAutoPlay(debridEnabled, activeResolverProviderId)
                    },
            )
        }
        if (mode == StreamAutoPlayMode.MANUAL) {
            return StreamAutoPlayEvaluation()
        }
        val preferredStream = orderedBingeGroupCandidates.firstOrNull { stream ->
            stream.isAutoPlayable(debridEnabled, activeResolverProviderId)
        }
        val matchingStreams = when (mode) {
            StreamAutoPlayMode.MANUAL -> emptyList()
            StreamAutoPlayMode.AUTOMATIC, StreamAutoPlayMode.SEMI_AUTOMATIC,
            StreamAutoPlayMode.FIRST_STREAM -> candidateStreams
            StreamAutoPlayMode.REGEX_MATCH -> {
                val pattern = regexPattern.trim()

                val userRegex = runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull()
                    ?: return StreamAutoPlayEvaluation()

                val exclusionMatches = Regex("\\(\\?![^)]*?\\(([^)]+)\\)").findAll(pattern)

                val exclusionWords = exclusionMatches
                    .flatMap { match -> match.groupValues[1].split("|") }
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .toList()

                val excludeRegex = if (exclusionWords.isNotEmpty()) {
                    Regex(
                        "\\b(${exclusionWords.joinToString("|") { Regex.escape(it) }})\\b",
                        RegexOption.IGNORE_CASE,
                    )
                } else null

                candidateStreams.filter { stream ->
                    val url = stream.playableDirectUrl.orEmpty()

                    val searchableText = buildString {
                        append(stream.addonName).append(' ')
                        append(stream.name.orEmpty()).append(' ')
                        append(stream.streamLabel).append(' ')
                        append(stream.description.orEmpty()).append(' ')
                        append(url)
                    }

                    if (!userRegex.containsMatchIn(searchableText)) return@filter false

                    if (excludeRegex != null && excludeRegex.containsMatchIn(searchableText)) {
                        return@filter false
                    }

                    true
                }
            }
        }
        val orderedMatchingStreams = prioritizeStreams(
            streams = matchingStreams,
            preferredAddonName = preferredAddonName,
            preferredQuality = preferredQuality,
            preferredSourceOrder = preferredSourceOrder,
        )
        if (orderedMatchingStreams.isEmpty() && preferredStream == null) return StreamAutoPlayEvaluation()

        val readyStreams = buildList {
            preferredStream?.let(::add)
            orderedMatchingStreams
                .filter { it.isAutoPlayable(debridEnabled, activeResolverProviderId) }
                .filterNot { it == preferredStream }
                .forEach(::add)
        }
        val selected = readyStreams.firstOrNull()
        if (selected != null) {
            return StreamAutoPlayEvaluation(
                stream = selected,
                readyStreams = readyStreams,
            )
        }

        return StreamAutoPlayEvaluation(
            readyStreams = readyStreams,
            hasPendingDebridCandidate = orderedMatchingStreams.any {
                it.isPendingDebridAutoPlay(debridEnabled, activeResolverProviderId)
            },
        )
    }

    private fun prioritizeStreams(
        streams: List<StreamItem>,
        preferredAddonName: String,
        preferredQuality: StreamAutoPlayPreferredQuality,
        preferredSourceOrder: List<String>,
    ): List<StreamItem> {
        val favourite = preferredAddonName.trim()
        val targetHeight = preferredQuality.targetHeight
        val eligibleStreams = if (targetHeight == null) streams else streams.filter { stream ->
            val height = stream.detectAutoPlayQualityHeight()
            height == null || height <= targetHeight
        }
        if (eligibleStreams.isEmpty()) return emptyList()
        if (eligibleStreams.size < 2) return eligibleStreams
        val sourceRank = preferredSourceOrder.withIndex().associate { it.value to it.index }
        return eligibleStreams.withIndex().sortedWith(
            compareBy<IndexedValue<StreamItem>> { indexed ->
                val stream = indexed.value
                val height = stream.detectAutoPlayQualityHeight()
                val isFavourite = favourite.isNotEmpty() && stream.addonName.equals(favourite, true)
                if (targetHeight == null) {
                    when {
                        isFavourite && height != null -> 0
                        isFavourite -> 1
                        favourite.isEmpty() && height != null -> 0
                        height != null -> 2
                        favourite.isEmpty() -> 1
                        else -> 3
                    }
                } else {
                    // Prefer the exact tier, then lower qualities; unknown quality is last resort.
                    when {
                        height == targetHeight && isFavourite -> 0
                        height == targetHeight -> 1
                        height != null && height < targetHeight && isFavourite -> 2
                        height != null && height < targetHeight -> 3
                        height == null && isFavourite -> 4
                        height == null -> 5
                        else -> 6
                    }
                }
            }.thenBy { indexed ->
                val height = indexed.value.detectAutoPlayQualityHeight()
                when {
                    height == null -> Int.MAX_VALUE
                    targetHeight == null -> -height
                    height >= targetHeight -> Int.MAX_VALUE
                    else -> -height
                }
            }.thenBy { sourceRank[it.value.addonName] ?: Int.MAX_VALUE }
                .thenBy { it.index },
        ).map { it.value }
    }

    private fun StreamItem.detectAutoPlayQualityHeight(): Int? {
        val metadata = listOf(
            name, title, description, url, behaviorHints.filename,
            clientResolve?.stream?.raw?.parsed?.rawTitle,
            clientResolve?.stream?.raw?.parsed?.resolution,
            clientResolve?.stream?.raw?.parsed?.quality,
        ).filterNotNull().joinToString(" ")
        val markers = listOf(
            Regex("""(?i)(?:\b4320p?\b|\b8k\b)""") to 4320,
            Regex("""(?i)(?:\b2160p?\b|\b4k\b|\buhd\b)""") to 2160,
            Regex("""(?i)\b1440p?\b""") to 1440,
            Regex("""(?i)(?:\b1080p?\b|\bfhd\b|\bfull\s*hd\b)""") to 1080,
            Regex("""(?i)(?:\b720p?\b|\bhd\b)""") to 720,
            Regex("""(?i)\b480p?\b""") to 480,
            Regex("""(?i)\b360p?\b""") to 360,
        )
        return markers.firstOrNull { (regex, _) -> regex.containsMatchIn(metadata) }?.second
    }

    private fun StreamItem.isAutoPlayable(
        debridEnabled: Boolean,
        activeResolverProviderId: String?,
    ): Boolean =
        playableDirectUrl != null ||
            serverTarget != null ||
            (
                AppFeaturePolicy.p2pEnabled &&
                    needsLocalDebridResolve &&
                    p2pInfoHash != null &&
                    !isPendingDebridAutoPlay(debridEnabled, activeResolverProviderId)
            ) ||
            (debridEnabled && isAddonDebridCandidate && isReadyDebridAutoPlay(activeResolverProviderId))

    private fun StreamItem.isReadyDebridAutoPlay(activeResolverProviderId: String?): Boolean =
        when {
            isDirectDebridStream -> clientResolve?.service.matchesResolver(activeResolverProviderId)
            isCachedDebridTorrentStream -> debridCacheStatus?.providerId.matchesResolver(activeResolverProviderId)
            else -> false
        }

    private fun StreamItem.isPendingDebridAutoPlay(
        debridEnabled: Boolean,
        activeResolverProviderId: String?,
    ): Boolean {
        if (!debridEnabled || !isInstalledAddonStream || !needsLocalDebridResolve) return false
        if (!debridCacheStatus?.providerId.matchesResolver(activeResolverProviderId)) return false
        val state = debridCacheStatus?.state
        return state == null || state == StreamDebridCacheState.CHECKING
    }

    private fun String?.matchesResolver(activeResolverProviderId: String?): Boolean {
        val active = activeResolverProviderId?.trim().orEmpty()
        return active.isBlank() || this == null || equals(active, ignoreCase = true)
    }
}

data class StreamAutoPlayEvaluation(
    val stream: StreamItem? = null,
    val readyStreams: List<StreamItem> = emptyList(),
    val hasPendingDebridCandidate: Boolean = false,
)
