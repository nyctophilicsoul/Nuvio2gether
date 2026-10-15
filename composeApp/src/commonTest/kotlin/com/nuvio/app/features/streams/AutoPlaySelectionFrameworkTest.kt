package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals

class AutoPlaySelectionFrameworkTest {

    @Test
    fun `requested quality is tried before progressively lower qualities`() {
        val candidates = listOf(
            candidate("720p", 720, 0),
            candidate("2160p", 2160, 1),
            candidate("1080p", 1080, 2),
            candidate("1440p", 1440, 3),
        )

        val ordered = AutoPlaySelectionFramework.orderCandidates(
            candidates,
            AutoPlayTargetQuality.UHD_4K,
        )

        assertEquals(listOf("2160p", "1440p", "1080p", "720p"), ordered.map { it.sourceId })
    }

    @Test
    fun `quality above selected target is not used as a fallback`() {
        val candidates = listOf(
            candidate("4k", 2160, 0),
            candidate("1080p", 1080, 1),
            candidate("720p", 720, 2),
        )

        val ordered = AutoPlaySelectionFramework.orderCandidates(
            candidates,
            AutoPlayTargetQuality.FULL_HD_1080,
        )

        assertEquals(listOf("1080p", "720p"), ordered.map { it.sourceId })
    }

    @Test
    fun `unknown quality comes after known quality fallbacks`() {
        val candidates = listOf(
            candidate("unknown", null, 0),
            candidate("720p", 720, 1),
            candidate("1080p", 1080, 2),
        )

        val ordered = AutoPlaySelectionFramework.orderCandidates(
            candidates,
            AutoPlayTargetQuality.FULL_HD_1080,
        )

        assertEquals(listOf("1080p", "720p", "unknown"), ordered.map { it.sourceId })
    }

    @Test
    fun `provider order is stable within the same quality tier`() {
        val candidates = listOf(
            candidate("first-1080p", 1080, 0),
            candidate("second-1080p", 1080, 1),
            candidate("720p", 720, 2),
        )

        val ordered = AutoPlaySelectionFramework.orderCandidates(
            candidates,
            AutoPlayTargetQuality.FULL_HD_1080,
        )

        assertEquals(listOf("first-1080p", "second-1080p", "720p"), ordered.map { it.sourceId })
    }

    @Test
    fun `quality detection recognises common resolution labels`() {
        assertEquals(2160, AutoPlaySelectionFramework.detectQualityHeight("Movie UHD 4K"))
        assertEquals(1440, AutoPlaySelectionFramework.detectQualityHeight("Episode 1440p"))
        assertEquals(1080, AutoPlaySelectionFramework.detectQualityHeight("Movie Full HD"))
        assertEquals(720, AutoPlaySelectionFramework.detectQualityHeight("HD release"))
        assertEquals(null, AutoPlaySelectionFramework.detectQualityHeight("Movie WEB-DL"))
    }

    @Test
    fun `fastest measured source wins within requested quality before lower quality`() {
        val candidates = listOf(
            candidate("slow-1080p", 1080, 0),
            candidate("fast-1080p", 1080, 1),
            candidate("fast-720p", 720, 2),
        )

        val ordered = AutoPlaySelectionFramework.rankByQualityThenLatency(
            candidates = candidates,
            target = AutoPlayTargetQuality.FULL_HD_1080,
            latencyMillisBySourceId = mapOf(
                "slow-1080p" to 900,
                "fast-1080p" to 120,
                "fast-720p" to 40,
            ),
        )

        assertEquals(listOf("fast-1080p", "slow-1080p", "fast-720p"), ordered.map { it.sourceId })
    }

    @Test
    fun `failed or unmeasured sources sort after measured sources within a quality tier`() {
        val candidates = listOf(
            candidate("unmeasured", 1080, 0),
            candidate("measured", 1080, 1),
        )

        val ordered = AutoPlaySelectionFramework.rankByQualityThenLatency(
            candidates = candidates,
            target = AutoPlayTargetQuality.FULL_HD_1080,
            latencyMillisBySourceId = mapOf("measured" to 250),
        )

        assertEquals(listOf("measured", "unmeasured"), ordered.map { it.sourceId })
    }

    @Test
    fun `provider priority is respected and unspecified providers remain stable`() {
        data class Provider(val id: String, val direct: Boolean = false)
        val providers = listOf(
            Provider("plugin-b"),
            Provider("addon-a"),
            Provider("plugin-a"),
            Provider("debrid", direct = true),
        )

        val ordered = AutoPlaySelectionFramework.orderProvidersByPriority(
            providers = providers,
            providerId = { it.id },
            priorityOrder = listOf("plugin-a", "addon-a"),
            isDirectDebrid = { it.direct },
        )

        assertEquals(listOf("debrid", "plugin-a", "addon-a", "plugin-b"), ordered.map { it.id })
    }

    @Test
    fun `quality counts reflect only matching 2k 1080p and 720p metadata`() {
        val candidates = listOf(
            candidate("2k-a", 1440, 0),
            candidate("2k-b", 1440, 1),
            candidate("1080p", 1080, 2),
            candidate("720p", 720, 3),
            candidate("4k", 2160, 4),
            candidate("unknown", null, 5),
        )

        val counts = AutoPlaySelectionFramework.countAvailableQualities(candidates)

        assertEquals(2, counts.qhd1440)
        assertEquals(1, counts.fullHd1080)
        assertEquals(1, counts.hd720)
        assertEquals(1, counts.lower)
    }

    @Test
    fun `empty candidate list stays empty`() {
        assertEquals(
            emptyList(),
            AutoPlaySelectionFramework.orderCandidates(emptyList(), AutoPlayTargetQuality.HD_720),
        )
    }

    private fun candidate(
        id: String,
        height: Int?,
        order: Int,
    ) = AutoPlaySourceCandidate(
        sourceId = id,
        value = id,
        qualityHeight = height,
        providerOrder = order,
    )
}
