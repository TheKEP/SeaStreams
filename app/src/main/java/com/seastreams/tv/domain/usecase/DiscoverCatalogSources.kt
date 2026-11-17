package com.seastreams.tv.domain.usecase

import com.seastreams.tv.domain.model.CatalogRowSpec
import com.seastreams.tv.domain.model.CatalogSource
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.StreamingProvider

/** Rows of the Movies and TV Shows tabs (PRD §5). */
object DiscoverCatalogSources {

    /**
     * `Popular Movies`/`Popular Shows`, then one row per enabled streaming service (TMDB `/discover`
     * filtered by watch provider). Stremio addon catalogs never appear here; they render on Home only.
     */
    fun rowSpecs(mediaType: MediaType, enabledProviders: List<StreamingProvider>): List<CatalogRowSpec> = buildList {
        val prefix = mediaType.name.lowercase()
        add(CatalogRowSpec("$prefix-popular", popularTitle(mediaType), CatalogSource.Discover(mediaType)))
        enabledProviders.forEach { provider ->
            add(CatalogRowSpec("$prefix-${provider.name.lowercase()}", provider.displayName, CatalogSource.Discover(mediaType, provider)))
        }
    }

    fun popularTitle(mediaType: MediaType): String = when (mediaType) {
        MediaType.MOVIE -> "Popular Movies"
        MediaType.TV -> "Popular Shows"
    }
}
