package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamAutoPlaySelectorTest {

    @Test
    fun `bingeGroup-first selects matching stream before first stream mode`() {
        val first = stream(
            addonName = "AddonA",
            url = "https://example.com/first.m3u8",
            name = "1080p",
            bingeGroup = "other-group",
        )
        val preferred = stream(
            addonName = "AddonB",
            url = "https://example.com/preferred.m3u8",
            name = "720p",
            bingeGroup = "same-group",
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(first, preferred),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA", "AddonB"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredBingeGroup = "same-group",
            preferBingeGroupInSelection = true,
        )

        assertEquals(preferred, selected)
    }

    @Test
    fun `falls back to normal mode when no bingeGroup match exists`() {
        val first = stream(
            addonName = "AddonA",
            url = "https://example.com/first.m3u8",
            name = "First",
            bingeGroup = "group-a",
        )
        val second = stream(
            addonName = "AddonB",
            url = "https://example.com/second.m3u8",
            name = "Second",
            bingeGroup = "group-b",
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(first, second),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA", "AddonB"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredBingeGroup = "missing-group",
            preferBingeGroupInSelection = true,
        )

        assertEquals(first, selected)
    }

    @Test
    fun `bingeGroup-first respects source and addon plugin filters`() {
        val filteredOutAddonMatch = stream(
            addonName = "AddonFilteredOut",
            url = "https://example.com/addon-match.m3u8",
            bingeGroup = "same-group",
        )
        val allowedPluginMatch = stream(
            addonName = "PluginAllowed",
            url = "https://example.com/plugin-match.m3u8",
            bingeGroup = "same-group",
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(filteredOutAddonMatch, allowedPluginMatch),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ENABLED_PLUGINS_ONLY,
            installedAddonNames = setOf("AddonFilteredOut"),
            selectedAddons = emptySet(),
            selectedPlugins = setOf("PluginAllowed"),
            preferredBingeGroup = "same-group",
            preferBingeGroupInSelection = true,
        )

        assertEquals(allowedPluginMatch, selected)
    }

    @Test
    fun `blank preferredBingeGroup behaves as disabled`() {
        val first = stream(
            addonName = "AddonA",
            url = "https://example.com/first.m3u8",
            bingeGroup = "group-a",
        )
        val second = stream(
            addonName = "AddonB",
            url = "https://example.com/second.m3u8",
            bingeGroup = "group-b",
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(first, second),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA", "AddonB"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredBingeGroup = "   ",
            preferBingeGroupInSelection = true,
        )

        assertEquals(first, selected)
    }

    @Test
    fun `manual mode remains manual even with matching bingeGroup`() {
        val matched = stream(
            addonName = "AddonA",
            url = "https://example.com/match.m3u8",
            bingeGroup = "same-group",
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(matched),
            mode = StreamAutoPlayMode.MANUAL,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredBingeGroup = "same-group",
            preferBingeGroupInSelection = true,
        )

        assertNull(selected)
    }

    @Test
    fun `first stream mode can select direct debrid candidate without resolved URL`() {
        val directDebrid = stream(
            addonName = "Torbox Instant",
            url = null,
            name = "TB Instant",
            directDebrid = true,
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(directDebrid),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = emptySet(),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
        )

        assertEquals(directDebrid, selected)
    }

    @Test
    fun `first stream mode does not auto select external url browser link`() {
        val external = stream(
            addonName = "External Addon",
            externalUrl = "https://example.com/watch",
            name = "Watch on site",
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(external),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("External Addon"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
        )

        assertNull(selected)
    }

    @Test
    fun `timeout evaluation keeps pending regex debrid candidate open`() {
        val pending = stream(
            addonName = "Torrentio",
            name = "The Show 1080p",
            infoHash = "hash-pending",
            cacheState = StreamDebridCacheState.CHECKING,
        )

        val evaluation = StreamAutoPlaySelector.evaluateAutoPlayStream(
            streams = listOf(pending),
            mode = StreamAutoPlayMode.REGEX_MATCH,
            regexPattern = "1080p",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("Torrentio"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            debridEnabled = true,
            activeResolverProviderId = "premiumize",
        )

        assertNull(evaluation.stream)
        assertTrue(evaluation.hasPendingDebridCandidate)
    }

    @Test
    fun `timeout evaluation still selects direct link while debrid candidate is pending`() {
        val pending = stream(
            addonName = "Torrentio",
            name = "The Show 1080p",
            infoHash = "hash-pending",
            cacheState = StreamDebridCacheState.CHECKING,
        )
        val direct = stream(
            addonName = "Direct Addon",
            url = "https://example.com/video.mp4",
            name = "The Show 1080p",
        )

        val evaluation = StreamAutoPlaySelector.evaluateAutoPlayStream(
            streams = listOf(pending, direct),
            mode = StreamAutoPlayMode.REGEX_MATCH,
            regexPattern = "1080p",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("Torrentio", "Direct Addon"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            debridEnabled = true,
            activeResolverProviderId = "premiumize",
        )

        assertEquals(direct, evaluation.stream)
        assertFalse(evaluation.hasPendingDebridCandidate)
    }

    @Test
    fun `direct debrid candidate must match active resolver`() {
        val torbox = stream(
            addonName = "Comet",
            name = "TB Instant",
            directDebrid = true,
            directDebridService = "torbox",
        )

        val evaluation = StreamAutoPlaySelector.evaluateAutoPlayStream(
            streams = listOf(torbox),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("Comet"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            debridEnabled = true,
            activeResolverProviderId = "premiumize",
        )

        assertNull(evaluation.stream)
        assertFalse(evaluation.hasPendingDebridCandidate)
    }

    @Test
    fun `nested exclusions do not crash regex selection`() {
        val stream = stream(
            addonName = "Direct Addon",
            url = "https://example.com/video.mp4",
            name = "Movie 1080p WEB",
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(stream),
            mode = StreamAutoPlayMode.REGEX_MATCH,
            regexPattern = "^(?!.*\\b(CAM(?!RIP)|TS)\\b).*1080p",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("Direct Addon"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
        )

        assertEquals(stream, selected)
    }

    @Test
    fun `preferred quality from another addon beats a lower-quality favourite result`() {
        val fav720 = stream(addonName = "Favourite", url = "https://example.com/fav-720.mp4", name = "Episode 720p")
        val other1080 = stream(addonName = "Other", url = "https://example.com/other-1080.mp4", name = "Episode 1080p")
        val other480 = stream(addonName = "Other", url = "https://example.com/other-480.mp4", name = "Episode 480p")
        val evaluation = StreamAutoPlaySelector.evaluateAutoPlayStream(
            streams = listOf(fav720, other1080, other480), mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "", source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("Favourite", "Other"), selectedAddons = emptySet(), selectedPlugins = emptySet(),
            preferredAddonName = "Favourite", preferredQuality = StreamAutoPlayPreferredQuality.P1080,
        )
        assertEquals(other1080, evaluation.stream)
        assertEquals(listOf(other1080, fav720, other480), evaluation.readyStreams)
    }

    @Test
    fun `favourite addon wins when quality preference is best available`() {
        val fav720 = stream(addonName = "Favourite", url = "https://example.com/fav-720.mp4", name = "Episode 720p")
        val other2160 = stream(addonName = "Other", url = "https://example.com/other-2160.mp4", name = "Episode 2160p")
        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(other2160, fav720), mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "", source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("Favourite", "Other"), selectedAddons = emptySet(), selectedPlugins = emptySet(),
            preferredAddonName = "Favourite",
        )
        assertEquals(fav720, selected)
    }

    @Test
    fun `preferred quality selects exact tier then lower fallback without jumping above it`() {
        val stream4k = stream(
            addonName = "FourK",
            url = "https://example.com/2160.mp4",
            name = "Movie 2160p",
        )
        val stream1080 = stream(
            addonName = "FullHD",
            url = "https://example.com/1080.mp4",
            name = "Movie 1080p",
        )
        val stream720 = stream(
            addonName = "HD",
            url = "https://example.com/720.mp4",
            name = "Movie 720p",
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(stream4k, stream720, stream1080),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("FourK", "FullHD", "HD"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredQuality = StreamAutoPlayPreferredQuality.P1080,
        )

        assertEquals(stream1080, selected)
    }

    @Test
    fun `preferred quality falls back to the best lower tier when exact tier is absent`() {
        val stream4k = stream(addonName = "FourK", url = "https://example.com/2160.mp4", name = "Movie 2160p")
        val stream720 = stream(addonName = "HD", url = "https://example.com/720.mp4", name = "Movie 720p")
        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(stream4k, stream720),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("FourK", "HD"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredQuality = StreamAutoPlayPreferredQuality.P1080,
        )
        assertEquals(stream720, selected)
    }

    @Test
    fun `preferred quality does not auto select a stream above the requested ceiling`() {
        val only4k = stream(addonName = "4K source", url = "https://example.com/2160.mp4", name = "Movie 2160p")
        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(only4k),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("4K source"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredQuality = StreamAutoPlayPreferredQuality.P1080,
        )
        assertNull(selected)
    }

    @Test
    fun `configured source priority breaks ties between equal quality streams`() {
        val lowerPriority = stream(addonName = "Addon B", url = "https://example.com/b.mp4", name = "Episode 1080p")
        val higherPriority = stream(addonName = "Addon A", url = "https://example.com/a.mp4", name = "Episode 1080p")

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(lowerPriority, higherPriority),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("Addon A", "Addon B"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredQuality = StreamAutoPlayPreferredQuality.P1080,
            preferredSourceOrder = listOf("Addon A", "Addon B"),
        )

        assertEquals(higherPriority, selected)
    }

    @Test
    fun `preferred quality ceiling also applies to binge-group selection`() {
        val stream4k = stream(
            addonName = "FourK",
            url = "https://example.com/2160.mp4",
            name = "Episode 2160p",
            bingeGroup = "series",
        )
        val stream720 = stream(
            addonName = "HD",
            url = "https://example.com/720.mp4",
            name = "Episode 720p",
            bingeGroup = "series",
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(stream4k, stream720),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("FourK", "HD"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredBingeGroup = "series",
            preferBingeGroupInSelection = true,
            preferredQuality = StreamAutoPlayPreferredQuality.P1080,
        )

        assertEquals(stream720, selected)
    }

    @Test
    fun `early threshold requires more than ten distinct streams of one quality from top three add-ons`() {
        val groups = listOf(
            AddonStreamGroup("A", "a", streams = (1..4).map { stream(addonName = "A", url = "https://a.example/$it.mp4", name = "Episode 1080p") }, isLoading = false),
            AddonStreamGroup("B", "b", streams = (1..4).map { stream(addonName = "B", url = "https://b.example/$it.mp4", name = "Episode 1080p") }, isLoading = false),
            AddonStreamGroup("C", "c", streams = (1..3).map { stream(addonName = "C", url = "https://c.example/$it.mp4", name = "Episode 1080p") }, isLoading = false),
            AddonStreamGroup("D", "d", streams = (1..20).map { stream(addonName = "D", url = "https://d.example/$it.mp4", name = "Episode 1080p") }, isLoading = false),
        )
        assertTrue(StreamAutoPlaySelector.hasEarlySelectionThreshold(groups, listOf("A", "B", "C", "D")))
    }

    @Test
    fun `early threshold does not combine qualities or count duplicate links`() {
        val groups = listOf(
            AddonStreamGroup("A", "a", streams = (1..6).map { stream(addonName = "A", url = "https://shared.example/$it.mp4", name = "Episode 1080p") }, isLoading = false),
            AddonStreamGroup("B", "b", streams = (1..5).map { stream(addonName = "B", url = "https://shared.example/$it.mp4", name = "Episode 720p") }, isLoading = false),
            AddonStreamGroup("C", "c", streams = (1..5).map { stream(addonName = "C", url = "https://shared.example/$it.mp4", name = "Episode 1080p") }, isLoading = false),
        )
        assertFalse(StreamAutoPlaySelector.hasEarlySelectionThreshold(groups, listOf("A", "B", "C")))
    }

    private fun stream(
        addonName: String,
        url: String? = null,
        externalUrl: String? = null,
        name: String? = null,
        bingeGroup: String? = null,
        directDebrid: Boolean = false,
        directDebridService: String = "torbox",
        infoHash: String? = null,
        cacheState: StreamDebridCacheState? = null,
    ): StreamItem = StreamItem(
        name = name,
        url = url,
        externalUrl = externalUrl,
        infoHash = infoHash,
        addonName = addonName,
        addonId = "addon:$addonName",
        clientResolve = if (directDebrid) {
            StreamClientResolve(
                type = "debrid",
                service = directDebridService,
                isCached = true,
                infoHash = "hash",
            )
        } else {
            null
        },
        debridCacheStatus = cacheState?.let { state ->
            StreamDebridCacheStatus(
                providerId = "premiumize",
                providerName = "Premiumize",
                state = state,
            )
        },
        behaviorHints = StreamBehaviorHints(
            bingeGroup = bingeGroup,
        ),
    )
}
