package com.nuvio.app.features.streams

/**
 * Quality-first ranking for automatic playback.
 *
 * This is deliberately independent from the legacy autoplay mode/regex settings:
 * an explicit quality chosen by the user locks candidate eligibility to that exact
 * resolution tier. Other tiers must never become silent playback fallbacks.
 */
object QualityFirstAutoPlayPolicy {

    fun availableQualities(
        streams: List<StreamItem>,
        debridEnabled: Boolean,
    ): List<StreamAutoPlayPreferredQuality> {
        val availableHeights = streams.asSequence()
            .filter { it.isSelectableForPlayback(debridEnabled) }
            .mapNotNull(::detectQualityHeight)
            .toSet()

        return StreamAutoPlayPreferredQuality.entries
            .filter { quality -> quality.targetHeight != null && quality.targetHeight in availableHeights }
            .sortedByDescending { it.targetHeight }
    }

    /**
     * For an explicit quality, only streams confidently classified at that exact
     * resolution are eligible. ANY means best-known quality first.
     */
    fun rankCandidates(
        streams: List<StreamItem>,
        requestedQuality: StreamAutoPlayPreferredQuality,
        debridEnabled: Boolean,
        preferredSourceOrder: List<String> = emptyList(),
    ): List<StreamItem> {
        val targetHeight = requestedQuality.targetHeight
        val candidates = streams
            .filter { it.isSelectableForPlayback(debridEnabled) }
            .distinctBy(::identityKey)
            .filter { stream ->
                val height = detectQualityHeight(stream)
                targetHeight == null || height == targetHeight
            }

        val sourceRank = preferredSourceOrder.withIndex().associate { it.value to it.index }
        // Parse tags once per candidate instead of running several regexes on every
        // comparator invocation. This keeps ranking responsive with large provider lists.
        val formatRanks = candidates.map(::formatPreferenceRank)
        val audioRanks = candidates.map(::audioPreferenceRank)
        return candidates.withIndex().sortedWith(
            compareBy<IndexedValue<StreamItem>> { indexed ->
                detectQualityHeight(indexed.value)?.let { height -> -height } ?: Int.MAX_VALUE
            }.thenBy { indexed ->
                sourceRank[indexed.value.addonName]
                    ?: sourceRank[indexed.value.addonId]
                    ?: Int.MAX_VALUE
            }.thenBy { indexed ->
                // Prefer premium source formats within a provider's configured tier.
                formatRanks[indexed.index]
            }.thenBy { indexed ->
                audioRanks[indexed.index]
            }.thenBy { it.index },
        ).map { it.value }
    }

    /**
     * Build the semi-automatic fallback chain. The selected resolution tier is
     * always exhausted first; only then may lower tiers be attempted. A selected
     * 1080p quality can therefore never jump upward to 1440p/4K.
     *
     * ANY keeps the existing best-known-quality-first ordering.
     */
    fun rankCandidatesWithFallback(
        streams: List<StreamItem>,
        requestedQuality: StreamAutoPlayPreferredQuality,
        debridEnabled: Boolean,
        preferredSourceOrder: List<String> = emptyList(),
    ): List<StreamItem> {
        val targetHeight = requestedQuality.targetHeight
            ?: return rankCandidates(
                streams = streams,
                requestedQuality = StreamAutoPlayPreferredQuality.ANY,
                debridEnabled = debridEnabled,
                preferredSourceOrder = preferredSourceOrder,
            )

        return StreamAutoPlayPreferredQuality.entries
            .asSequence()
            .filter { quality -> quality.targetHeight != null && quality.targetHeight <= targetHeight }
            .sortedByDescending { it.targetHeight }
            .flatMap { quality ->
                rankCandidates(
                    streams = streams,
                    requestedQuality = quality,
                    debridEnabled = debridEnabled,
                    preferredSourceOrder = preferredSourceOrder,
                ).asSequence()
            }
            .distinctBy(::identityKey)
            .toList()
    }

    /**
     * Prefer Blu-ray remux/disc sources, then Dolby Vision and HDR when candidates
     * are otherwise comparable. Resolution remains the primary ranking criterion,
     * so a format tag never overrides the requested quality ceiling.
     */
    private fun formatPreferenceRank(stream: StreamItem): Int {
        val metadata = listOf(
            stream.name,
            stream.title,
            stream.description,
            stream.behaviorHints.filename,
            stream.clientResolve?.stream?.raw?.parsed?.rawTitle,
            stream.clientResolve?.stream?.raw?.parsed?.parsedTitle,
            stream.clientResolve?.stream?.raw?.parsed?.quality,
        ).filterNotNull().joinToString(" ").lowercase()

        val bluRay = Regex("""\b(?:blu.?ray|bdremux|remux|bdrip|brrip)\b""").containsMatchIn(metadata)
        val dolbyVision = Regex("""\b(?:dolby.?vision|dovi)\b""").containsMatchIn(metadata)
        val hdr = Regex("""\b(?:hdr|hdr10\+?|hlg)\b""").containsMatchIn(metadata)
        // Disc/remux first; Dolby Vision next; HDR next; ordinary SDR last.
        return when {
            bluRay -> 0
            dolbyVision -> 1
            hdr -> 2
            else -> 3
        }
    }

    /** Prefer higher-fidelity audio when video quality and visual format are comparable. */
    private fun audioPreferenceRank(stream: StreamItem): Int {
        val metadata = listOf(
            stream.name,
            stream.title,
            stream.description,
            stream.behaviorHints.filename,
            stream.clientResolve?.stream?.raw?.parsed?.rawTitle,
            stream.clientResolve?.stream?.raw?.parsed?.parsedTitle,
        ).filterNotNull().joinToString(" ").lowercase()

        return when {
            Regex("""\b(?:truehd|dolby.?atmos|dts.?hd.?ma|dts.?x)\b""").containsMatchIn(metadata) -> 0
            Regex("""\b(?:flac|pcm|lpcm|dts.?hd|dts.?ma)\b""").containsMatchIn(metadata) -> 1
            Regex("""\b(?:eac3|e.?ac.?3|ddp|ac3|dolby.?digital.?plus)\b""").containsMatchIn(metadata) -> 2
            Regex("""\b(?:aac|mp3|stereo)\b""").containsMatchIn(metadata) -> 3
            else -> 4
        }
    }

    fun detectQualityHeight(stream: StreamItem): Int? {
        val parsed = stream.clientResolve?.stream?.raw?.parsed

        // Resolution is the strongest signal. The old implementation concatenated
        // every metadata field and searched for the highest token, so a title or
        // description containing "4K" could override a real 1080p filename.
        parsed?.resolution?.let(::qualityHeightIn)?.let { return it }
        stream.behaviorHints.filename?.let(::qualityHeightIn)?.let { return it }
        parsed?.parsedTitle?.let(::qualityHeightIn)?.let { return it }
        parsed?.rawTitle?.let(::qualityHeightIn)?.let { return it }
        parsed?.quality?.let(::qualityHeightIn)?.let { return it }
        stream.name?.let(::qualityHeightIn)?.let { return it }
        stream.title?.let(::qualityHeightIn)?.let { return it }
        return stream.description?.let(::qualityHeightIn)
    }

    private fun qualityHeightIn(metadata: String): Int? {
        val dimensions = Regex("""(?i)\b\d{3,4}\s*[x×]\s*(4320|2160|1440|1080|720|480|360)\b""")
            .find(metadata)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (dimensions != null) return dimensions

        val markers = listOf(
            Regex("""(?i)(?:\b4320p?\b|\b8k\b)""") to 4320,
            Regex("""(?i)(?:\b2160p?\b|\b4k\b|\buhd\b)""") to 2160,
            Regex("""(?i)(?:\b1440p?\b|\b2k\b|\bqhd\b)""") to 1440,
            Regex("""(?i)(?:\b1080p?\b|\bfhd\b|\bfull\s*hd\b)""") to 1080,
            Regex("""(?i)\b720p\b""") to 720,
            Regex("""(?i)\b480p?\b""") to 480,
            Regex("""(?i)\b360p?\b""") to 360,
        )
        return markers.firstOrNull { (regex, _) -> regex.containsMatchIn(metadata) }?.second
    }

    private fun identityKey(stream: StreamItem): String {
        stream.playableDirectUrl?.let { return "url:$it" }
        stream.p2pInfoHash?.let { return "p2p:${it.lowercase()}:${stream.p2pFileIdx ?: -1}" }
        stream.serverTarget?.let { return "server:${it.item.encode()}:${it.mediaSourceId.orEmpty()}" }
        return "stream:${stream.addonId}:${stream.streamLabel}:${stream.behaviorHints.filename.orEmpty()}"
    }
}
