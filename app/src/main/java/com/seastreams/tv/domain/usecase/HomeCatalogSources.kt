package com.seastreams.tv.domain.usecase

import com.seastreams.tv.core.database.entity.CustomCatalogEntity
import com.seastreams.tv.domain.model.CatalogRowSpec

/** The fixed MDBLists behind the Home hero and rows (PRD §3). */
object HomeCatalogSources {
    const val HERO_ADULT_MOVIES = "https://mdblist.com/lists/official/movies/justwatch-streaming-charts"
    const val HERO_ADULT_SHOWS = "https://mdblist.com/lists/official/shows/justwatch-streaming-charts"

    const val KIDS_MOVIES = "https://mdblist.com/lists/snoak/trending-kids-movies"
    const val KIDS_SHOWS = "https://mdblist.com/lists/tvgeniekodi/trending-kids-tv-shows"

    val ADULT_ROWS: List<CatalogRowSpec> = listOf(
        CatalogRowSpec("most-watched-movies", "Most Watched Movies", "https://mdblist.com/lists/official/movies/most-watched-week"),
        CatalogRowSpec("most-watched-shows", "Most Watched Shows", "https://mdblist.com/lists/official/shows/most-watched-week"),
    )

    val KIDS_ROWS: List<CatalogRowSpec> = listOf(
        CatalogRowSpec("trending-kids-movies", "Trending Kids Movies", KIDS_MOVIES),
        CatalogRowSpec("trending-kids-shows", "Trending Kids Shows", KIDS_SHOWS),
        CatalogRowSpec("latest-family-movies", "Latest Family Movies", "https://mdblist.com/lists/kep084/latest-family-movies"),
        CatalogRowSpec("latest-family-shows", "Latest Family Shows", "https://mdblist.com/lists/kep084/latest-family-shows"),
    )

    /** Fixed rows for the profile type followed by the user's Home MDBLists, in portal order. */
    fun rowSpecs(isKids: Boolean, custom: List<CustomCatalogEntity>): List<CatalogRowSpec> =
        (if (isKids) KIDS_ROWS else ADULT_ROWS) +
            custom.map { CatalogRowSpec(id = "custom-${it.id}", title = it.title, listUrl = it.listUrl) }
}
