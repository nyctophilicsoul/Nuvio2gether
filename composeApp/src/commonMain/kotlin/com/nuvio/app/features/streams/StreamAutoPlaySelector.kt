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
        val preferredReadyStream = bingeGroupCandidates.firstOrNull { stream ->
            stream.isAutoPlayable(debridEnabled, activeResolverProviderId)
        }
        if (bingeGroupOnly) {
            val readyStreams = preferredReadyStream?.let(::listOf).orEmpty()
            return StreamAutoPlayEvaluation(
                stream = preferredReadyStream,
                readyStreams = readyStreams,
                hasPendingDebridCandidate = preferredReadyStream == null &&
                    bingeGroupCandidates.any {
                        it.isPendingDebridAutoPlay(debridEnabled, activeResolverProviderId)
                    },
            )
        }
        if (mode == StreamAutoPlayMode.MANUAL) {
            return StreamAutoPlayEvaluation()
        }
        val preferredStream = if (preferBingeGroupInSelection && targetBingeGroup.isNotEmpty()) {
            candidateStreams.firstOrNull { stream ->
                stream.behaviorHints.bingeGroup == targetBingeGroup &&
                    stream.isAutoPlayable(debridEnabled, activeResolverProviderId)
            }
        } else {
            null
        }
        val matchingStreams = when (mode) {
            StreamAutoPlayMode.MANUAL -> emptyList()
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
    ): List<StreamItem> {
        if (streams.size < 2) {
            return if (preferredQuality.targetHeight == null) streams else
                AutoPlaySelectionFramework.candidatesFor(
                    streams = streams,
                    target = preferredQuality.toAutoPlayTargetQuality(),
                ).map { it.value }
        }

        // A selected quality is a ceiling. Keep the exact quality first, then only
        // lower-quality fallbacks; never silently switch to a higher quality.
        if (preferredQuality.targetHeight != null) {
            return AutoPlaySelectionFramework.candidatesFor(
                streams = streams,
                target = preferredQuality.toAutoPlayTargetQuality(),
            ).map { it.value }
        }

        // Preserve legacy favourite-provider behavior only when the user has not
        // selected a quality target.
        val favourite = preferredAddonName.trim()
        return streams.withIndex().sortedWith(
            compareBy<IndexedValue<StreamItem>> { indexed ->
                val stream = indexed.value
                val height = stream.detectAutoPlayQualityHeight()
                val isFavourite = favourite.isNotEmpty() && stream.addonName.equals(favourite, true)
                when {
                    isFavourite && height != null -> 0
                    isFavourite -> 1
                    favourite.isEmpty() && height != null -> 0
                    height != null -> 2
                    favourite.isEmpty() -> 1
                    else -> 3
                }
            }.thenBy { indexed ->
                indexed.value.detectAutoPlayQualityHeight()?.let { -it } ?: Int.MAX_VALUE
            }.thenBy { it.index },
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


private fun StreamAutoPlayPreferredQuality.toAutoPlayTargetQuality(): AutoPlayTargetQuality =
    when (targetHeight) {
        2160 -> AutoPlayTargetQuality.UHD_4K
        1440 -> AutoPlayTargetQuality.QHD_1440
        1080 -> AutoPlayTargetQuality.FULL_HD_1080
        720 -> AutoPlayTargetQuality.HD_720
        480 -> AutoPlayTargetQuality.SD_480
        360 -> AutoPlayTargetQuality.SD_360
        else -> AutoPlayTargetQuality.UHD_4K
    }
