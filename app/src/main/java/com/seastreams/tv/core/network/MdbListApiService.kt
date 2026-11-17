package com.seastreams.tv.core.network

import com.seastreams.tv.domain.model.MediaType
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNames
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** One entry of an MDBList `/json` export. `id` is the TMDB id. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class MdbListItem(
    @SerialName("id") val tmdbId: Int? = null,
    val rank: Int = 0,
    val adult: Int = 0,
    val title: String = "",
    @SerialName("imdb_id") val imdbId: String? = null,
    @JsonNames("tvdbid", "tvdb_id") val tvdbId: Int? = null,
    @SerialName("mediatype") val mediaTypeRaw: String = "",
    @SerialName("release_year") val releaseYear: Int? = null,
) {
    val isAdult: Boolean get() = adult != 0

    val mediaType: MediaType?
        get() = when (mediaTypeRaw.lowercase()) {
            "movie" -> MediaType.MOVIE
            "show", "tv", "series" -> MediaType.TV
            else -> null
        }
}

/** Parses user-supplied MDBList links (from the Web Portal) into canonical list paths. */
object MdbListUrl {

    /** Base that canonical list URLs are built on (`CANONICAL_BASE + listPath(input)`). */
    const val CANONICAL_BASE = "https://mdblist.com"

    private val ALLOWED_HOSTS = setOf("mdblist.com", "www.mdblist.com")
    private const val JSON_SUFFIX = "json"
    private const val MIN_SEGMENTS = 3 // "lists", owner, slug (official lists add a category segment)

    /**
     * Returns the canonical list path, e.g. `/lists/official/movies/most-watched-week`, or `null`
     * when [input] is not an MDBList list link. Query strings, fragments, trailing slashes and an
     * existing `/json` suffix are discarded.
     */
    fun listPath(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = when {
            trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            else -> "https://$trimmed"
        }
        val url = withScheme.toHttpUrlOrNull() ?: return null
        if (url.host !in ALLOWED_HOSTS) return null
        val segments = url.pathSegments.filter { it.isNotEmpty() }.toMutableList()
        if (segments.lastOrNull()?.equals(JSON_SUFFIX, ignoreCase = true) == true) segments.removeAt(segments.lastIndex)
        if (segments.size < MIN_SEGMENTS || segments.first() != "lists") return null
        return "/" + segments.joinToString("/")
    }

    /** The public JSON export URL for [input] (its list path with `/json` appended). */
    fun toJsonUrl(input: String, baseUrl: String = CANONICAL_BASE): String? =
        listPath(input)?.let { baseUrl.trimEnd('/') + it + "/" + JSON_SUFFIX }
}

/** Fetches MDBList list exports by appending `/json` to the list URL. */
@Singleton
class MdbListApiService @Inject constructor(
    private val client: HttpClient,
    private val endpoints: ApiEndpoints,
    private val json: Json,
) {

    /** @throws IllegalArgumentException when [listUrl] is not an MDBList list link. */
    suspend fun fetchList(listUrl: String): List<MdbListItem> {
        val jsonUrl = requireNotNull(MdbListUrl.toJsonUrl(listUrl, endpoints.mdbListBaseUrl)) {
            "Not an MDBList list URL: $listUrl"
        }
        // Decoded from text so a wrong/missing Content-Type header cannot break parsing.
        val body = client.get(jsonUrl).bodyAsText()
        return json.decodeFromString(ListSerializer(MdbListItem.serializer()), body)
    }
}
