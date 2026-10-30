package com.nuvio.app.features.home

import kotlin.test.Test
import com.nuvio.app.features.catalog.CatalogTarget
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MergedHomeRowsTest {
    @Test
    fun sourceTabsIsTheDefaultDisplayMode() {
        assertEquals(MergedHomeRowDisplayMode.SOURCE_TABS, MergedHomeRowDisplayMode.fromStorage(null))
        assertEquals(MergedHomeRowDisplayMode.SOURCE_TABS.storageValue, MergedHomeRowDefinition(
            id = "default",
            title = "Default",
            sourceKeys = listOf("source-a", "source-b"),
        ).displayMode)
    }

    private fun item(id: String, type: String = "movie", name: String = id) =
        MetaPreview(id = id, type = type, name = name)

    @Test
    fun mergedRowsCanOpenEvenWhenTheirPreviewIsShorterThanTheShelfLimit() {
        val section = HomeCatalogSection(
            key = mergedHomeRowKey("test"),
            title = "Latest Indian",
            subtitle = "Merged row",
            addonName = "Merged row",
            target = CatalogTarget.MergedRow(rowId = "test"),
            items = listOf(item("one")),
            isMergedRow = true,
        )

        assertTrue(section.canOpenCatalog(previewLimit = 18))
    }

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
    fun mergeDeduplicatesAcrossPreviouslyLoadedSourcePages() {
        val merged = mergeHomeRowItems(
            sourceItems = listOf(
                listOf(item("a1"), item("a2")),
                listOf(item("b1"), item("a1"), item("b2")),
            ),
            deduplicate = true,
            maxItems = Int.MAX_VALUE,
        )

        assertEquals(listOf("a1", "b1", "a2", "b2"), merged.map { it.id })
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
