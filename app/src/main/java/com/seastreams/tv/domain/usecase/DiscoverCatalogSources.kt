package com.seastreams.tv.domain.usecase

import com.seastreams.tv.core.database.entity.CatalogPlacement
import com.seastreams.tv.core.database.entity.CustomCatalogEntity
import com.seastreams.tv.domain.model.CatalogRowSpec
import com.seastreams.tv.domain.model.CatalogSource
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.StreamingProvider

/** Rows of the Movies and TV Shows tabs (PRD §5). */
object DiscoverCatalogSources {

    fun placementFor(mediaType: MediaType): CatalogPlacement = when (mediaType) {
        MediaType.MOVIE -> CatalogPlacement.MOVIES
        MediaType.TV -> CatalogPlacement.TV_SHOWS
    }

    /**
     * `Popular Movies`/`Popular Shows`, then one row per enabled streaming service (TMDB `/discover`
     * filtered by watch provider), then the user's MDBLists assigned to this tab, in portal order.
     */
    fun rowSpecs(
        mediaType: MediaType,
        enabledProviders: List<StreamingProvider>,
        custom: List<CustomCatalogEntity>,
    ): List<CatalogRowSpec> = buildList {
        val prefix = mediaType.name.lowercase()
        add(CatalogRowSpec("$prefix-popular", popularTitle(mediaType), CatalogSource.Discover(mediaType)))
        enabledProviders.forEach { provider ->
            add(CatalogRowSpec("$prefix-${provider.name.lowercase()}", provider.displayName, CatalogSource.Discover(mediaType, provider)))
        }
        custom.forEach { add(CatalogRowSpec(id = "custom-${it.id}", title = it.title, listUrl = it.listUrl)) }
    }

    fun popularTitle(mediaType: MediaType): String = when (mediaType) {
        MediaType.MOVIE -> "Popular Movies"
        MediaType.TV -> "Popular Shows"
    }
}
