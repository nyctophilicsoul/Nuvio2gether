package com.nuvio.app.features.localfiles

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LocalVideoFileTest {
    @Test
    fun matchKeyIsStableForSameNormalizedFilenameAndSize() {
        val first = localVideoMatchKey("A.Movie.2024.mkv", 1_250_000L)
        val second = localVideoMatchKey("a.movie.2024.mkv", 1_250_000L)

        assertEquals(first, second)
        assertTrue(first.startsWith("local:"))
    }

    @Test
    fun matchKeyChangesWhenFileSizeChanges() {
        assertNotEquals(
            localVideoMatchKey("A.Movie.2024.mkv", 1_250_000L),
            localVideoMatchKey("A.Movie.2024.mkv", 1_250_001L),
        )
    }

    @Test
    fun matchKeyIgnoresRepeatedWhitespaceAndPaths() {
        assertEquals(
            localVideoMatchKey(" A   Movie.mkv ", 1024L),
            localVideoMatchKey("A Movie.mkv", 1024L),
        )
        assertEquals(
            localVideoMatchKey("A Movie.mkv", 1024L),
            localVideoMatchKey("A Movie.mkv", 1024L),
        )
    }

    @Test
    fun matchKeyDoesNotIncludeSourceUri() {
        val first = LocalVideoFile("content://one/private/path", "film.mkv", 100).matchKey
        val second = LocalVideoFile("content://another/device/uri", "film.mkv", 100).matchKey
        assertEquals(first, second)
    }
}
