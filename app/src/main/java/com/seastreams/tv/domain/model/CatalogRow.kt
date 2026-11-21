package com.seastreams.tv.domain.model

/** Items shown on one horizontal row; [hasMore] means the source has titles beyond the cap. */
data class CatalogRow(val items: List<MediaItem>, val hasMore: Boolean)

/** Where a row's (or a View More grid's) titles come from. */
sealed interface CatalogSource {
    /** A public mdblist.com list (the built-in Home hero and rows). */
    data class MdbList(val listUrl: String) : CatalogSource

    /** TMDB `/discover`, most popular first, optionally limited to one streaming service or genre (PRD §5). */
    data class Discover(
        val mediaType: MediaType,
        val provider: StreamingProvider? = null,
        val genreId: Int? = null,
    ) : CatalogSource

    /** TMDB `/trending/{type}/week`. */
    data class Trending(val mediaType: MediaType) : CatalogSource

    /**
     * The Kids `Trending Family Movies` / `Trending Family Shows` rows: curated TMDB `/discover`
     * queries whose genre and certification rules live in `FamilyCatalogPolicy` (PRD §3).
     */
    data class FamilyTrending(val mediaType: MediaType) : CatalogSource

    /**
     * A catalog declared in an enabled Stremio addon's manifest (`/catalog/{type}/{id}.json`).
     * Shown on Home only, beneath the built-in rows (PRD §3).
     */
    data class Addon(val manifestUrl: String, val catalogId: String, val mediaType: MediaType) : CatalogSource
}

/** One catalog row: a stable id, its on-screen title and where its titles come from. */
data class CatalogRowSpec(val id: String, val title: String, val source: CatalogSource) {
    /** An MDBList-backed row. */
    constructor(id: String, title: String, listUrl: String) : this(id, title, CatalogSource.MdbList(listUrl))
}

sealed interface RowContent {
    data object Loading : RowContent
    data class Loaded(val row: CatalogRow) : RowContent
    data object Failed : RowContent
}

/** One catalog row as it progresses from loading to loaded/failed. */
data class CatalogRowState(val spec: CatalogRowSpec, val content: RowContent)
