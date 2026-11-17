package com.seastreams.tv.core.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Decides how long a successful GET response may be served from the HTTP disk cache.
 *
 * PRD §5 requires TMDB/MDBList responses to be cached for 6–12 hours regardless of the
 * origin's own headers (TMDB sends `max-age=300`, MDBList `max-age=86400`):
 * - TMDB `/external_ids`, `/find/{imdb_id}` and title details (`/movie/{id}`, `/tv/{id}/…`) → [stableMetadataTtl]
 * - All other TMDB reads (`/discover`, `/search`, …) → [catalogTtl]
 * - TMDB `/configuration` → not cached (it is the API-key validation call)
 * - MDBList `…/json` list exports → [catalogTtl]
 * - Anything else (Stremio addons, images) → origin headers are respected (`null`).
 */
class HttpCachePolicy(
    endpoints: ApiEndpoints,
    val catalogTtl: Duration = 6.hours,
    val stableMetadataTtl: Duration = 12.hours,
) {
    private val tmdbBase: HttpUrl = endpoints.tmdbBaseUrl.toHttpUrl()
    private val mdbListBase: HttpUrl = endpoints.mdbListBaseUrl.toHttpUrl()

    init {
        require(catalogTtl in ALLOWED_TTL_RANGE) { "catalogTtl must be within 6–12h, was $catalogTtl" }
        require(stableMetadataTtl in ALLOWED_TTL_RANGE) {
            "stableMetadataTtl must be within 6–12h, was $stableMetadataTtl"
        }
    }

    fun ttlFor(request: Request): Duration? {
        if (request.method != "GET") return null
        val url = request.url
        relativeSegments(url, tmdbBase)?.let { return tmdbTtl(it) }
        relativeSegments(url, mdbListBase)?.let { return mdbListTtl(it) }
        return null
    }

    private fun tmdbTtl(segments: List<String>): Duration? {
        val root = segments.firstOrNull() ?: return null
        if (root == "configuration") return null
        val isTitleScoped = root == "find" ||
            (root == "movie" || root == "tv") && segments.getOrNull(1)?.toIntOrNull() != null
        return if (isTitleScoped) stableMetadataTtl else catalogTtl
    }

    private fun mdbListTtl(segments: List<String>): Duration? =
        if (segments.firstOrNull() == "lists" && segments.lastOrNull() == "json") catalogTtl else null

    /** Path segments of [url] below [base], or `null` when [url] is not under [base]. */
    private fun relativeSegments(url: HttpUrl, base: HttpUrl): List<String>? {
        if (url.scheme != base.scheme || url.host != base.host || url.port != base.port) return null
        val baseSegments = base.pathSegments.filter { it.isNotEmpty() }
        val segments = url.pathSegments.filter { it.isNotEmpty() }
        if (segments.size < baseSegments.size || segments.subList(0, baseSegments.size) != baseSegments) return null
        return segments.drop(baseSegments.size)
    }

    companion object {
        val ALLOWED_TTL_RANGE: ClosedRange<Duration> = 6.hours..12.hours
    }
}
