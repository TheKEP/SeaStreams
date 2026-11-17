package com.seastreams.tv.core.network

import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.StreamingProvider
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import javax.inject.Inject
import javax.inject.Singleton

/** Thrown when a TMDB call is attempted before the user has saved an API key. */
class TmdbApiKeyMissingException : IllegalStateException("No TMDB API key has been configured")

/**
 * TMDB v3 client. Responses flow through the shared OkHttp client, so they are disk-cached for
 * 6–12h ([HttpCachePolicy]) and 429s are retried with backoff ([RateLimitRetryInterceptor]).
 *
 * `/external_ids` is exposed **only** as a single-title lookup ([getExternalIds]); there is
 * deliberately no list/batch variant (CLAUDE.md rule 5). Callers must invoke it lazily from
 * the Details screen, never while loading catalog rows.
 */
@Singleton
class TmdbApiService @Inject constructor(
    private val client: HttpClient,
    private val endpoints: ApiEndpoints,
    private val apiKeyProvider: TmdbApiKeyProvider,
) {

    /**
     * Checks a candidate key against `/configuration` (never cached).
     * @return `true` for a working key, `false` when TMDB answers 401 Unauthorized.
     */
    suspend fun validateApiKey(candidateKey: String): Boolean {
        val key = candidateKey.trim()
        if (key.isEmpty()) return false
        return try {
            client.get(url("configuration")) { authenticate(key) }
            true
        } catch (e: ClientRequestException) {
            if (e.response.status == HttpStatusCode.Unauthorized) false else throw e
        }
    }

    suspend fun discoverMovies(
        provider: StreamingProvider? = null,
        page: Int = 1,
        genreId: Int? = null,
    ): TmdbPage<TmdbMovieSummary> = get("discover", "movie") { discoverParameters(provider, page, genreId) }

    suspend fun discoverTv(
        provider: StreamingProvider? = null,
        page: Int = 1,
        genreId: Int? = null,
    ): TmdbPage<TmdbTvSummary> = get("discover", "tv") { discoverParameters(provider, page, genreId) }

    /** This week's trending movies (Search's default `Trending Movies` category). */
    suspend fun trendingMovies(page: Int = 1): TmdbPage<TmdbMovieSummary> =
        get("trending", "movie", TRENDING_WINDOW) { pageParameters(page) }

    /** This week's trending shows (Search's `Trending Shows` category). */
    suspend fun trendingTv(page: Int = 1): TmdbPage<TmdbTvSummary> =
        get("trending", "tv", TRENDING_WINDOW) { pageParameters(page) }

    /** Every TMDB genre for [mediaType] (`/genre/movie/list` or `/genre/tv/list`). */
    suspend fun genres(mediaType: MediaType): List<TmdbGenre> =
        get<TmdbGenreList>("genre", mediaType.tmdbPath, "list") { parameter("language", LANGUAGE) }.genres

    suspend fun searchMovies(query: String, page: Int = 1): TmdbPage<TmdbMovieSummary> =
        get("search", "movie") { searchParameters(query, page) }

    suspend fun searchTv(query: String, page: Int = 1): TmdbPage<TmdbTvSummary> =
        get("search", "tv") { searchParameters(query, page) }

    /** Movie details with US release certifications, cast and title logos in a single request. */
    suspend fun getMovieDetails(tmdbId: Int): TmdbMovieDetails = get("movie", tmdbId.toString()) {
        parameter("language", LANGUAGE)
        parameter("append_to_response", "release_dates,credits,images")
        parameter("include_image_language", IMAGE_LANGUAGES)
    }

    /** Show details with US content ratings, cast and title logos in a single request. */
    suspend fun getTvDetails(tmdbId: Int): TmdbTvDetails = get("tv", tmdbId.toString()) {
        parameter("language", LANGUAGE)
        parameter("append_to_response", "content_ratings,credits,images")
        parameter("include_image_language", IMAGE_LANGUAGES)
    }

    /** Episodes of one season of a show (titles, air dates, runtimes, stills). */
    suspend fun getSeason(tvId: Int, seasonNumber: Int): TmdbSeasonDetails =
        get("tv", tvId.toString(), "season", seasonNumber.toString()) { parameter("language", LANGUAGE) }

    /** Trailers/teasers for one title; fetched only when the user presses `Trailer`. */
    suspend fun getVideos(mediaType: MediaType, tmdbId: Int): TmdbVideos =
        get(mediaType.tmdbPath, tmdbId.toString(), "videos") { parameter("language", LANGUAGE) }

    /**
     * The TMDB title(s) for an IMDb id (`tt…`), used to map Stremio addon catalog entries onto TMDB.
     * Not `/external_ids`; HTTP-cached like other per-title metadata.
     */
    suspend fun findByImdbId(imdbId: String): TmdbFindResult = get("find", imdbId) {
        parameter("external_source", "imdb_id")
        parameter("language", LANGUAGE)
    }

    /** Single-title IMDb/TVDB id lookup. One call → exactly one `/external_ids` request. */
    suspend fun getExternalIds(mediaType: MediaType, tmdbId: Int): TmdbExternalIds =
        get(mediaType.tmdbPath, tmdbId.toString(), "external_ids")

    private suspend inline fun <reified T> get(
        vararg pathSegments: String,
        crossinline configure: HttpRequestBuilder.() -> Unit = {},
    ): T {
        val key = apiKeyProvider.currentApiKey()?.takeIf { it.isNotBlank() } ?: throw TmdbApiKeyMissingException()
        return client.get(url(*pathSegments)) {
            authenticate(key)
            configure()
        }.body()
    }

    private fun url(vararg pathSegments: String): String =
        endpoints.tmdbBaseUrl.trimEnd('/') + "/" + pathSegments.joinToString("/")

    /** v4 read-access tokens (JWTs) go in a Bearer header; v3 keys go in the `api_key` query. */
    private fun HttpRequestBuilder.authenticate(key: String) {
        if (isV4AccessToken(key)) header(HttpHeaders.Authorization, "Bearer $key") else parameter("api_key", key)
    }

    private fun HttpRequestBuilder.discoverParameters(provider: StreamingProvider?, page: Int, genreId: Int?) {
        if (genreId != null) parameter("with_genres", genreId)
        if (provider != null) {
            parameter("with_watch_providers", provider.watchProviderFilter)
            parameter("watch_region", WATCH_REGION)
            parameter("with_watch_monetization_types", "flatrate")
        }
        parameter("sort_by", "popularity.desc")
        parameter("include_adult", false)
        pageParameters(page)
    }

    private fun HttpRequestBuilder.pageParameters(page: Int) {
        parameter("language", LANGUAGE)
        parameter("page", page)
    }

    private fun HttpRequestBuilder.searchParameters(query: String, page: Int) {
        parameter("query", query.trim())
        parameter("include_adult", false)
        parameter("language", LANGUAGE)
        parameter("page", page)
    }

    companion object {
        const val LANGUAGE = "en-US"
        const val WATCH_REGION = "US"
        const val TRENDING_WINDOW = "week"

        /** TMDB list endpoints return at most this many results per page. */
        const val PAGE_SIZE = 20

        /** Artwork languages for `images`: English plus language-neutral ("null") logos. */
        const val IMAGE_LANGUAGES = "en,null"

        fun isV4AccessToken(key: String): Boolean = key.startsWith("eyJ") && key.count { it == '.' } == 2
    }
}
