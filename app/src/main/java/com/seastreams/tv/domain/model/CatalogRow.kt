package com.seastreams.tv.domain.model

/** Items shown on one horizontal row; [hasMore] means the source has titles beyond the cap. */
data class CatalogRow(val items: List<MediaItem>, val hasMore: Boolean)

/** Where one row's MDBList lives and what it is called on screen. */
data class CatalogRowSpec(val id: String, val title: String, val listUrl: String)

sealed interface RowContent {
    data object Loading : RowContent
    data class Loaded(val row: CatalogRow) : RowContent
    data object Failed : RowContent
}

/** One Home row as it progresses from loading to loaded/failed. */
data class CatalogRowState(val spec: CatalogRowSpec, val content: RowContent)
