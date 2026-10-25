package com.nuvio.app.features.party

import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PartySourceMatchTest {
    private fun stream(
        name: String = "1080p WEB-DL",
        addonId: String = "addon.example",
        filename: String? = null,
        infoHash: String? = null,
        fileIdx: Int? = null,
        videoHash: String? = null,
    ) = StreamItem(
        name = name,
        addonName = "Example",
        addonId = addonId,
        infoHash = infoHash,
        fileIdx = fileIdx,
        behaviorHints = StreamBehaviorHints(filename = filename, videoHash = videoHash),
    )

    @Test
    fun httpStreamDoesNotMatchByAddonAndLabelWhenFilenamesDiffer() {
        val source = PartySource(
            filename = "Movie.2024.1080p.WEB-DL.mkv",
            addonId = "addon.example",
            name = "1080p WEB-DL",
        )
        assertFalse(source.matches(stream(filename = "Movie.2024.WEB-DL.1080p.mkv")))
    }

    @Test
    fun httpStreamDoesNotMatchByAddonAndLabelWhenFilenameIsMissing() {
        val source = PartySource(addonId = "addon.example", name = "1080p WEB-DL")
        assertFalse(source.matches(stream()))
    }

    @Test
    fun equalNormalizedFilenameMatchesAcrossAddons() {
        val source = PartySource(filename = "/cache/Movie.2024.mkv", addonId = "other", name = "Other")
        assertTrue(source.matches(stream(addonId = "addon.example", filename = "movie.2024.MKV")))
    }

    @Test
    fun httpStreamMatchesByVideoHashAcrossAddons() {
        val source = PartySource(
            filename = "Old.Name.mkv",
            videoHash = "ABC123",
            addonId = "addon.one",
            name = "Release A",
        )
        assertTrue(
            source.matches(
                stream(
                    name = "Release B",
                    addonId = "addon.two",
                    filename = "Different.Name.mkv",
                    videoHash = "abc123",
                ),
            ),
        )
    }

    @Test
    fun differingVideoHashesOverrideMatchingFilename() {
        val source = PartySource(filename = "Movie.mkv", videoHash = "aaa111")
        assertFalse(source.matches(stream(filename = "Movie.mkv", videoHash = "bbb222")))
    }

    @Test
    fun videoHashPresentOnOnlyOneSideDoesNotFallBackToFilename() {
        val source = PartySource(filename = "Movie.mkv", videoHash = "aaa111")
        assertFalse(source.matches(stream(filename = "Movie.mkv")))
    }

    @Test
    fun torrentHashMismatchNeverFallsBackToMatchingLabel() {
        val source = PartySource(infoHash = "aaaaaaaa", filename = "Movie.mkv", addonId = "addon.example", name = "1080p WEB-DL")
        assertFalse(source.matches(stream(filename = "Movie.mkv", infoHash = "bbbbbbbb")))
    }

    @Test
    fun torrentIdentityPresentOnOnlyOneSideDoesNotFallBackToFilename() {
        val source = PartySource(infoHash = "aaaaaaaa", filename = "Movie.mkv")
        assertFalse(source.matches(stream(filename = "Movie.mkv")))
    }

    @Test
    fun torrentFileIndexMismatchDoesNotMatch() {
        val source = PartySource(infoHash = "aaaaaaaa", fileIdx = 1, addonId = "addon.example", name = "1080p WEB-DL")
        assertFalse(source.matches(stream(infoHash = "aaaaaaaa", fileIdx = 2)))
    }

    @Test
    fun sameTorrentHashWithoutIndexesRequiresFilenameMatch() {
        val source = PartySource(infoHash = "aaaaaaaa", filename = "Episode.01.mkv")
        assertFalse(source.matches(stream(infoHash = "aaaaaaaa", filename = "Episode.02.mkv")))
    }
}
