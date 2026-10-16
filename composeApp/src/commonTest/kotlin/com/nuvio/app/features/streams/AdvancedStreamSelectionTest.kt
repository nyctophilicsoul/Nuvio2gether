package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdvancedStreamSelectionTest {
    private fun stream(id: String, addon: String, quality: AdvancedStreamQuality, plugin: Boolean = false, index: Int = 0) =
        AdvancedStreamCandidate(id, addon, addon, plugin, quality, index)

    @Test fun `timeouts are independent and have requested defaults`() {
        val settings = AdvancedAutoPlaySettings()
        assertEquals(15, settings.addonFetchTimeoutSeconds)
        assertEquals(10, settings.playbackStartupTimeoutSeconds)
        assertEquals(10, settings.maxBufferEvents)
    }

    @Test fun `quality is inferred from metadata and unknown stays unknown`() {
        assertEquals(AdvancedStreamQuality.FHD_1080, AdvancedStreamQuality.fromMetadata("WEB-DL 1080p"))
        assertEquals(AdvancedStreamQuality.UHD_4K, AdvancedStreamQuality.fromMetadata("2160p HDR"))
        assertEquals(AdvancedStreamQuality.UNKNOWN, AdvancedStreamQuality.fromMetadata("best quality"))
    }

    @Test fun `plan exhausts chosen quality before lower quality and never upgrades`() {
        val input = listOf(
            stream("4k", "A", AdvancedStreamQuality.UHD_4K),
            stream("1080-a", "B", AdvancedStreamQuality.FHD_1080, index = 1),
            stream("720", "C", AdvancedStreamQuality.HD_720, index = 2),
            stream("1080-b", "A", AdvancedStreamQuality.FHD_1080, index = 3),
        )
        val plan = AdvancedStreamSelection.planForQuality(input, AdvancedStreamQuality.FHD_1080, AdvancedAutoPlaySettings())
        assertEquals(listOf("1080-a", "1080-b", "720"), plan.map { it.id })
        assertFalse(plan.any { it.quality == AdvancedStreamQuality.UHD_4K })
    }

    @Test fun `startup deadline fails only the current stream before playback starts`() {
        val attempt = AdvancedPlaybackAttempt("bad-link")
        val (failed, outcome) = AdvancedPlaybackGuard.onStartupDeadline(attempt, AdvancedAutoPlaySettings())
        assertEquals(AdvancedPlaybackOutcome.RETRY_NEXT_STREAM, outcome)
        assertTrue("bad-link" in failed.failedStreamIds)
        val (started, startedOutcome) = AdvancedPlaybackGuard.onStartupDeadline(
            AdvancedPlaybackGuard.onPlaybackStarted(attempt), AdvancedAutoPlaySettings()
        )
        assertEquals(AdvancedPlaybackOutcome.KEEP_PLAYING, startedOutcome)
        assertTrue(started.failedStreamIds.isEmpty())
    }

    @Test fun `buffer event threshold retries after more than configured limit`() {
        val settings = AdvancedAutoPlaySettings(maxBufferEvents = 2)
        var attempt = AdvancedPlaybackAttempt("link")
        repeat(2) {
            val result = AdvancedPlaybackGuard.onBuffering(attempt, settings)
            attempt = result.first
            assertEquals(AdvancedPlaybackOutcome.KEEP_PLAYING, result.second)
        }
        val (failed, outcome) = AdvancedPlaybackGuard.onBuffering(attempt, settings)
        assertEquals(AdvancedPlaybackOutcome.RETRY_NEXT_STREAM, outcome)
        assertTrue("link" in failed.failedStreamIds)
    }
}
