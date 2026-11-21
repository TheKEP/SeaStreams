package com.seastreams.tv.domain.usecase

import com.seastreams.tv.domain.model.CatalogRowSpec
import com.seastreams.tv.domain.model.CatalogSource
import com.seastreams.tv.domain.model.MediaType

/** The fixed sources behind the Home hero and rows (PRD §3): MDBLists, except the Kids rows, which are TMDB. */
object HomeCatalogSources {
    const val HERO_ADULT_MOVIES = "https://mdblist.com/lists/official/movies/justwatch-streaming-charts"
    const val HERO_ADULT_SHOWS = "https://mdblist.com/lists/official/shows/justwatch-streaming-charts"

    /** Kids hero pool only (PRD §3); no Kids row uses it. */
    const val KIDS_MOVIES = "https://mdblist.com/lists/snoak/trending-kids-movies"

    /** Kids hero pool only (PRD §3); no Kids row uses it. */
    const val KIDS_SHOWS = "https://mdblist.com/lists/tvgeniekodi/trending-kids-tv-shows"

    val ADULT_ROWS: List<CatalogRowSpec> = listOf(
        CatalogRowSpec("most-watched-movies", "Most Watched Movies", "https://mdblist.com/lists/official/movies/most-watched-week"),
        CatalogRowSpec("most-watched-shows", "Most Watched Shows", "https://mdblist.com/lists/official/shows/most-watched-week"),
    )

    /** Kids rows: only the two curated TMDB family rows, directly below Continue Watching. */
    val KIDS_ROWS: List<CatalogRowSpec> = listOf(
        CatalogRowSpec("trending-family-movies", "Trending Family Movies", CatalogSource.FamilyTrending(MediaType.MOVIE)),
        CatalogRowSpec("trending-family-shows", "Trending Family Shows", CatalogSource.FamilyTrending(MediaType.TV)),
    )

    /**
     * The built-in rows for the profile type (ending with `Most Watched Shows` for Adults and
     * `Trending Family Shows` for Kids), followed directly by [addonRows] — the enabled Stremio addons'
     * catalogs from [AddonCatalogSources.rowSpecs], in installed addon order.
     */
    fun rowSpecs(isKids: Boolean, addonRows: List<CatalogRowSpec>): List<CatalogRowSpec> =
        (if (isKids) KIDS_ROWS else ADULT_ROWS) + addonRows
}
