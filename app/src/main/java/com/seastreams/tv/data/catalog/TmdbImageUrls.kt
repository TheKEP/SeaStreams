package com.seastreams.tv.data.catalog

import com.seastreams.tv.core.network.ApiEndpoints
import javax.inject.Inject
import javax.inject.Singleton

/** Builds sized TMDB image CDN URLs from the relative paths TMDB returns (e.g. `/abc.jpg`). */
@Singleton
class TmdbImageUrls @Inject constructor(private val endpoints: ApiEndpoints) {

    fun poster(path: String?): String? = build(POSTER_SIZE, path)

    fun backdrop(path: String?): String? = build(BACKDROP_SIZE, path)

    fun heroBackdrop(path: String?): String? = build(HERO_BACKDROP_SIZE, path)

    fun logo(path: String?): String? = build(LOGO_SIZE, path)

    /** 16:9 episode still for the Episodes list. */
    fun still(path: String?): String? = build(STILL_SIZE, path)

    private fun build(size: String, path: String?): String? =
        path?.takeIf { it.isNotBlank() }?.let { "${endpoints.tmdbImageBaseUrl.trimEnd('/')}/$size$it" }

    private companion object {
        const val POSTER_SIZE = "w342"
        const val BACKDROP_SIZE = "w780"
        const val HERO_BACKDROP_SIZE = "w1280"
        const val LOGO_SIZE = "w500"
        const val STILL_SIZE = "w300"
    }
}
