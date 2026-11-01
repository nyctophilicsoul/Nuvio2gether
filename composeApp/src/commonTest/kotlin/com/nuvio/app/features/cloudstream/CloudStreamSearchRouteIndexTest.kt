package com.nuvio.app.features.cloudstream

import kotlin.test.Test
import kotlin.test.assertEquals

class CloudStreamSearchRouteIndexTest {
    @Test
    fun cachedRoutesMustMatchRequestedContentType() {
        val providerId = "route-index-type-test"
        CloudStreamSearchRouteIndex.remember(
            providerId = providerId,
            query = "The Office",
            items = listOf(
                CloudStreamSearchItem(
                    providerId = providerId,
                    data = "movie-route",
                    name = "The Office",
                    type = CloudStreamTvType.Movie,
                    year = 1993,
                ),
                CloudStreamSearchItem(
                    providerId = providerId,
                    data = "series-route",
                    name = "The Office",
                    type = CloudStreamTvType.TvSeries,
                    year = 2005,
                ),
            ),
        )

        assertEquals(
            listOf("movie-route"),
            CloudStreamSearchRouteIndex.find(
                providerId = providerId,
                titles = listOf("The Office"),
                type = "movie",
                year = 1993,
            ).map { it.data },
        )
        assertEquals(
            listOf("series-route"),
            CloudStreamSearchRouteIndex.find(
                providerId = providerId,
                titles = listOf("The Office"),
                type = "tv",
                year = 2005,
            ).map { it.data },
        )
    }

    @Test
    fun cachedRoutesMustMatchFullCanonicalTitleRatherThanPrefix() {
        val providerId = "route-index-prefix-test"
        CloudStreamSearchRouteIndex.remember(
            providerId = providerId,
            query = "Breaking",
            items = listOf(
                CloudStreamSearchItem(
                    providerId = providerId,
                    data = "breaking-bad-route",
                    name = "Breaking Bad",
                    type = CloudStreamTvType.TvSeries,
                ),
            ),
        )

        assertEquals(
            emptyList(),
            CloudStreamSearchRouteIndex.find(
                providerId = providerId,
                titles = listOf("Breaking"),
                type = "series",
                year = null,
            ),
        )
    }

    @Test
    fun cachedRoutesNormalizeArticlesAndTrailingYears() {
        val providerId = "route-index-canonical-test"
        CloudStreamSearchRouteIndex.remember(
            providerId = providerId,
            query = "Tank",
            items = listOf(
                CloudStreamSearchItem(
                    providerId = providerId,
                    data = "the-tank-2023-route",
                    name = "The Tank 2023",
                    type = CloudStreamTvType.Movie,
                    year = 2023,
                ),
            ),
        )

        assertEquals(
            listOf("the-tank-2023-route"),
            CloudStreamSearchRouteIndex.find(
                providerId = providerId,
                titles = listOf("Tank"),
                type = "movie",
                year = 2023,
            ).map { it.data },
        )
    }
}
