package com.nuvio.app.features.home

import kotlin.test.Test
import kotlin.test.assertEquals

class MergedHomeRowsTest {
    private fun item(id: String, type: String = "movie", name: String = id) =
        MetaPreview(id = id, type = type, name = name)

    @Test
    fun mergeInterleavesSourcesRatherThanExhaustingFirstSource() {
        val merged = mergeHomeRowItems(
            sourceItems = listOf(
                listOf(item("a1"), item("a2"), item("a3")),
                listOf(item("b1"), item("b2")),
            ),
            deduplicate = true,
        )

        assertEquals(listOf("a1", "b1", "a2", "b2", "a3"), merged.map { it.id })
    }

    @Test
    fun mergeDeduplicatesByMediaTypeAndIdNotTitle() {
        val merged = mergeHomeRowItems(
            sourceItems = listOf(
                listOf(item("tt1", name = "Original title"), item("tt2", name = "Same title")),
                listOf(item("tt1", name = "Localized title"), item("tt3", name = "Same title")),
            ),
            deduplicate = true,
        )

        assertEquals(listOf("tt1", "tt2", "tt3"), merged.map { it.id })
    }

    @Test
    fun mergeKeepsSameIdAcrossDifferentMediaTypes() {
        val merged = mergeHomeRowItems(
            sourceItems = listOf(
                listOf(item("same-id", type = "movie")),
                listOf(item("same-id", type = "series")),
            ),
            deduplicate = true,
        )

        assertEquals(listOf("movie:same-id", "series:same-id"), merged.map { it.stableKey() })
    }

    @Test
    fun mergeCanPreserveDuplicatesWhenDeduplicationIsDisabled() {
        val merged = mergeHomeRowItems(
            sourceItems = listOf(
                listOf(item("tt1")),
                listOf(item("tt1")),
            ),
            deduplicate = false,
        )

        assertEquals(listOf("tt1", "tt1"), merged.map { it.id })
    }
}
