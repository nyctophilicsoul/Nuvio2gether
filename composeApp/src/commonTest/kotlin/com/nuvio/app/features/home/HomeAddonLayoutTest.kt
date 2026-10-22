package com.nuvio.app.features.home

import kotlin.test.Test
import kotlin.test.assertEquals

class HomeAddonLayoutTest {
    @Test
    fun `normal add-ons preserve existing per-row order`() {
        val definitions = listOf(
            definition("A", "one"),
            definition("C", "one"),
            definition("B", "one"),
            definition("C", "two"),
            definition("D", "one"),
        )

        assertEquals(
            listOf("A:one", "C:one", "B:one", "C:two", "D:one"),
            orderHomeCatalogDefinitions(definitions, snapshot(definitions)).map(::label),
        )
    }

    @Test
    fun `locked add-on rows stay together at their saved position`() {
        val definitions = listOf(
            definition("A", "one"),
            definition("C", "one"),
            definition("B", "one"),
            definition("C", "two"),
            definition("D", "one"),
        )
        val state = snapshot(
            definitions = definitions,
            layouts = mapOf("C" to HomeAddonLayoutPreference(
                mode = HomeAddonLayoutMode.LOCKED,
                order = 1,
                anchorOrder = 1,
            )),
        )

        assertEquals(
            listOf("A:one", "C:one", "C:two", "B:one", "D:one"),
            orderHomeCatalogDefinitions(definitions, state).map(::label),
        )
    }

    @Test
    fun `locked add-on keeps its anchor when the add-on replaces catalog identifiers`() {
        val definitions = listOf(
            definition("A", "one"),
            definition("B", "one"),
            definition("C", "new"),
            definition("D", "one"),
        )
        val state = snapshot(
            definitions = definitions,
            layouts = mapOf("C" to HomeAddonLayoutPreference(
                mode = HomeAddonLayoutMode.LOCKED,
                order = 4,
                anchorOrder = 2,
            )),
            explicitOrders = mapOf(
                "A:movie:one" to 0,
                "B:movie:one" to 1,
                "C:movie:new" to 5,
                "D:movie:one" to 3,
            ),
        )

        assertEquals(
            listOf("A:one", "B:one", "C:new", "D:one"),
            orderHomeCatalogDefinitions(definitions, state).map(::label),
        )
    }

    @Test
    fun `locked add-on inserts new catalogs before a stable row and individual pins override the group`() {
        val definitions = listOf(
            definition("A", "one"),
            definition("C", "one"),
            definition("B", "one"),
            definition("C", "two"),
            definition("D", "one"),
            definition("C", "new"),
        )
        val anchor = "B:movie:one"
        val locked = snapshot(
            definitions = definitions,
            layouts = mapOf("C" to HomeAddonLayoutPreference(
                mode = HomeAddonLayoutMode.LOCKED,
                order = 1,
                anchorOrder = 1,
                anchorKey = anchor,
            )),
        )
        assertEquals(
            listOf("A:one", "C:one", "C:two", "C:new", "B:one", "D:one"),
            orderHomeCatalogDefinitions(definitions, locked).map(::label),
        )

        val individuallyPinned = locked.copy(
            preferences = locked.preferences + (
                "C:movie:one" to locked.preferences.getValue("C:movie:one").copy(
                    rowPlacement = HomeCatalogRowPlacement.PINNED_TOP,
                )
            ),
        )
        assertEquals(
            listOf("C:one", "A:one", "C:two", "C:new", "B:one", "D:one"),
            orderHomeCatalogDefinitions(definitions, individuallyPinned).map(::label),
        )
    }

    @Test
    fun `pinning add-on to top or bottom groups its current and future rows`() {
        val definitions = listOf(
            definition("A", "one"),
            definition("C", "one"),
            definition("B", "one"),
            definition("C", "two"),
            definition("D", "one"),
        )
        val top = snapshot(
            definitions = definitions,
            layouts = mapOf("C" to HomeAddonLayoutPreference(
                mode = HomeAddonLayoutMode.PINNED_TOP,
                order = 1,
                anchorOrder = 1,
            )),
        )
        val bottom = snapshot(
            definitions = definitions,
            layouts = mapOf("C" to HomeAddonLayoutPreference(
                mode = HomeAddonLayoutMode.PINNED_BOTTOM,
                order = 1,
                anchorOrder = 1,
            )),
        )

        assertEquals(
            listOf("C:one", "C:two", "A:one", "B:one", "D:one"),
            orderHomeCatalogDefinitions(definitions, top).map(::label),
        )
        assertEquals(
            listOf("A:one", "B:one", "D:one", "C:one", "C:two"),
            orderHomeCatalogDefinitions(definitions, bottom).map(::label),
        )
    }

    @Test
    fun `actual home settings order groups pinned add-on rows`() {
        val items = listOf(
            settingItem("A", "one", 0),
            settingItem("C", "one", 1),
            settingItem("B", "one", 2),
            settingItem("C", "two", 3),
            settingItem("D", "one", 4),
        )
        val addonIds = items.filterNot { it.isCollection }
            .associate { it.key to it.key.substringBefore(":movie:") }
        val layouts = mapOf(
            "C" to HomeAddonLayoutPreference(
                mode = HomeAddonLayoutMode.PINNED_TOP,
                order = 1,
                anchorOrder = 1,
            ),
        )

        assertEquals(
            listOf("C:one", "C:two", "A:one", "B:one", "D:one"),
            orderHomeCatalogSettingsItems(items, addonIds, layouts).map(::settingLabel),
        )
    }

    @Test
    fun `actual home settings order keeps locked add-on anchor after catalog IDs change`() {
        val items = listOf(
            settingItem("A", "one", 0),
            settingItem("B", "one", 1),
            settingItem("D", "one", 3),
            settingItem("C", "new", 5),
            settingItem("C", "latest", 6),
        )
        val addonIds = items.filterNot { it.isCollection }
            .associate { it.key to it.key.substringBefore(":movie:") }
        val layouts = mapOf(
            "C" to HomeAddonLayoutPreference(
                mode = HomeAddonLayoutMode.LOCKED,
                order = 2,
                anchorOrder = 2,
            ),
        )

        assertEquals(
            listOf("A:one", "B:one", "C:new", "C:latest", "D:one"),
            orderHomeCatalogSettingsItems(items, addonIds, layouts).map(::settingLabel),
        )
    }

    @Test
    fun `pinned collections retain priority when add-ons are pinned as a group`() {
        val items = listOf(
            HomeCatalogSettingsItem(
                key = "collection_favorites",
                defaultTitle = "Favorites",
                addonName = "Favorites",
                order = 0,
                isCollection = true,
                isPinnedToTop = true,
            ),
            settingItem("A", "one", 1),
            settingItem("C", "one", 2),
            settingItem("B", "one", 3),
        )
        val addonIds = items.filterNot { it.isCollection }
            .associate { it.key to it.key.substringBefore(":movie:") }
        val layouts = mapOf(
            "C" to HomeAddonLayoutPreference(
                mode = HomeAddonLayoutMode.PINNED_TOP,
                order = 2,
                anchorOrder = 2,
            ),
        )

        assertEquals(
            listOf("collection_favorites", "C:one", "A:one", "B:one"),
            orderHomeCatalogSettingsItems(items, addonIds, layouts).map(::settingLabel),
        )
    }

    @Test
    fun `individual row pins are removed from addon group and ordered in pin sections`() {
        val items = listOf(
            settingItem("A", "one", 0),
            settingItem("A", "two", 1).copy(rowPlacement = HomeCatalogRowPlacement.PINNED_TOP),
            HomeCatalogSettingsItem(
                key = "collection_favorites",
                defaultTitle = "Favorites",
                addonName = "Favorites",
                order = 2,
                isCollection = true,
            ),
            settingItem("B", "one", 3).copy(rowPlacement = HomeCatalogRowPlacement.PINNED_BOTTOM),
        )
        val addonIds = items.filterNot { it.isCollection }
            .associate { it.key to it.key.substringBefore(":movie:") }

        val ordered = orderHomeCatalogSettingsItems(items, addonIds, emptyMap())

        assertEquals(
            listOf("A:two", "A:one", "collection_favorites", "B:one"),
            ordered.map(::settingLabel),
        )
        assertEquals(1, ordered.count { it.key == "A:two" })
        assertEquals(1, ordered.count { it.key == "B:one" })
    }

    @Test
    fun `pin-at-position row keeps its place when its add-on is pinned to bottom`() {
        val items = listOf(
            settingItem("A", "one", 0),
            settingItem("C", "one", 1),
            settingItem("B", "one", 2),
            settingItem("C", "two", 3).copy(rowPlacement = HomeCatalogRowPlacement.PINNED_POSITION),
            settingItem("D", "one", 4),
            settingItem("C", "new", 5),
        )
        val addonIds = items.filterNot { it.isCollection }
            .associate { it.key to it.key.substringBefore(":movie:") }
        val layouts = mapOf("C" to HomeAddonLayoutPreference(
            mode = HomeAddonLayoutMode.PINNED_BOTTOM,
            order = 1,
            anchorOrder = 1,
        ))
        val expected = listOf("A:one", "B:one", "C:two", "D:one", "C:one", "C:new")

        assertEquals(expected, orderHomeCatalogSettingsItems(items, addonIds, layouts).map(::settingLabel))

        val definitions = listOf(
            definition("A", "one"),
            definition("C", "one"),
            definition("B", "one"),
            definition("C", "two"),
            definition("D", "one"),
            definition("C", "new"),
        )
        val baseState = snapshot(definitions, layouts = layouts)
        val state = baseState.copy(
            preferences = baseState.preferences + (
                "C:movie:two" to baseState.preferences.getValue("C:movie:two").copy(
                    rowPlacement = HomeCatalogRowPlacement.PINNED_POSITION,
                )
            ),
        )
        assertEquals(expected, orderHomeCatalogDefinitions(definitions, state).map(::label))
    }

    private fun settingItem(addonId: String, catalogId: String, order: Int) =
        HomeCatalogSettingsItem(
            key = "$addonId:movie:$catalogId",
            defaultTitle = "$addonId $catalogId",
            addonName = "Addon $addonId",
            order = order,
            addonId = addonId,
        )

    private fun settingLabel(item: HomeCatalogSettingsItem): String =
        if (item.isCollection) item.key else
            item.key.substringBefore(":movie:") + ":" + item.key.substringAfterLast(":")

    private fun snapshot(
        definitions: List<HomeCatalogDefinition>,
        layouts: Map<String, HomeAddonLayoutPreference> = emptyMap(),
        explicitOrders: Map<String, Int> = emptyMap(),
    ): HomeCatalogSettingsSnapshot = HomeCatalogSettingsSnapshot(
        heroEnabled = true,
        showCatalogType = true,
        hideUnreleasedContent = false,
        preferences = definitions.associate { definition ->
            definition.key to HomeCatalogPreference(
                customTitle = "",
                enabled = true,
                heroSourceEnabled = true,
                order = explicitOrders[definition.key]
                    ?: definitions.indexOf(definition),
            )
        },
        addonLayouts = layouts,
    )

    private fun definition(addonId: String, catalogId: String): HomeCatalogDefinition =
        HomeCatalogDefinition(
            key = "$addonId:movie:$catalogId",
            defaultTitle = "$addonId $catalogId",
            catalogName = catalogId,
            addonName = "Addon $addonId",
            manifestUrl = "https://example.com/$addonId/manifest.json",
            type = "movie",
            catalogId = catalogId,
            supportsPagination = false,
            descriptorSignature = "test",
        )

    private fun label(definition: HomeCatalogDefinition): String =
        definition.key.substringBefore(":movie:") + ":" + definition.catalogId
}
