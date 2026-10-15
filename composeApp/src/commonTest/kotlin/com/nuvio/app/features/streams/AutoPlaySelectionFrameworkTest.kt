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
