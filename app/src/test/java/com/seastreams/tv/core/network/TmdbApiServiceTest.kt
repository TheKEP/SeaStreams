package com.seastreams.tv.core.network

import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.StreamingProvider
import com.seastreams.tv.testutil.TestHttpServer
import io.ktor.client.HttpClient
import io.ktor.client.plugins.ServerResponseException
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import kotlinx.coroutines.test.runTest
import okhttp3.Cache
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class TmdbApiServiceTest {

    @TempDir
    lateinit var cacheDir: File

    private var apiKey: String? = V3_KEY
    private val discoverThrottles = AtomicInteger(0)

    private lateinit var server: TestHttpServer
    private lateinit var cache: Cache
    private lateinit var http: HttpClient
    private lateinit var service: TmdbApiService

    @BeforeEach
    fun setUp() {
        server = TestHttpServer {
            get("/3/configuration") {
                val authorised = call.request.queryParameters["api_key"] == V3_KEY ||
                    call.request.header("Authorization") == "Bearer $V4_TOKEN"
                if (authorised) call.respondText("{}", ContentType.Application.Json)
                else call.respondText("""{"status_code":7}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
            }
            get("/3/discover/movie") {
                if (discoverThrottles.getAndDecrement() > 0) {
                    call.respondText("", status = HttpStatusCode.TooManyRequests)
                } else {
                    call.respondText(DISCOVER_JSON, ContentType.Application.Json)
                }
            }
            get("/3/discover/tv") {
                call.respondText("""{"page":1,"total_pages":1,"total_results":0,"results":[]}""", ContentType.Application.Json)
            }
            get("/3/movie/{id}/external_ids") {
                call.respondText(
                    """{"id":${call.parameters["id"]},"imdb_id":"tt0137523","tvdb_id":null}""",
                    ContentType.Application.Json,
                )
            }
            get("/3/tv/{id}/external_ids") {
                call.respondText("""{"id":1399,"imdb_id":"tt0944947","tvdb_id":121361}""", ContentType.Application.Json)
            }
            get("/3/movie/550") { call.respondText(MOVIE_DETAILS_JSON, ContentType.Application.Json) }
            get("/3/tv/1399") { call.respondText(TV_DETAILS_JSON, ContentType.Application.Json) }
            get("/3/trending/movie/week") { call.respondText(DISCOVER_JSON, ContentType.Application.Json) }
            get("/3/genre/movie/list") {
                call.respondText("""{"genres":[{"id":28,"name":"Action"},{"id":12,"name":"Adventure"}]}""", ContentType.Application.Json)
            }
            get("/3/search/tv") {
                call.respondText("boom", status = HttpStatusCode.InternalServerError)
            }
        }
        val endpoints = ApiEndpoints(tmdbBaseUrl = server.url("/3"), mdbListBaseUrl = server.url("/mdb"))
        cache = Cache(cacheDir, 10L * 1024 * 1024)
        val okHttp = NetworkModule.buildOkHttpClient(cache, HttpCachePolicy(endpoints), RateLimitRetryInterceptor(sleeper = { }))
        http = NetworkModule.buildKtorClient(okHttp, NetworkModule.provideJson())
        service = TmdbApiService(http, endpoints) { apiKey }
    }

    @AfterEach
    fun tearDown() {
        http.close()
        cache.close()
        server.close()
    }

    @Test
    fun `external ids is a single-title lookup issuing exactly one request`() = runTest {
        val ids = service.getExternalIds(MediaType.MOVIE, 550)

        assertEquals("tt0137523", ids.imdbId)
        assertEquals(listOf("/3/movie/550/external_ids"), server.requests.map { it.path })
    }

    @Test
    fun `external ids for shows use the tv path`() = runTest {
        val ids = service.getExternalIds(MediaType.TV, 1399)

        assertEquals("tt0944947", ids.imdbId)
        assertEquals(121361, ids.tvdbId)
        assertEquals(1, server.hits("/3/tv/1399/external_ids"))
    }

    @Test
    fun `no external ids API accepts a batch of ids`() {
        val batchTypes = listOf(Collection::class.java, Iterable::class.java, Sequence::class.java)
        val externalIdMethods = TmdbApiService::class.java.methods.filter { it.name.contains("externalIds", ignoreCase = true) }

        assertTrue(externalIdMethods.isNotEmpty())
        externalIdMethods.flatMap { it.parameterTypes.toList() }.forEach { type ->
            assertFalse(type.isArray, "external ids must not take arrays/varargs")
            assertTrue(batchTypes.none { it.isAssignableFrom(type) }, "external ids must not take $type")
        }
    }

    @Test
    fun `v3 keys are sent as api_key query parameter`() = runTest {
        service.discoverMovies()

        val request = server.requests.single()
        assertEquals(V3_KEY, request.queryParam("api_key"))
        assertNull(request.header("Authorization"))
    }

    @Test
    fun `profile gate discover asks TMDB for the certification ceiling without excluded genres`() = runTest {
        service.discoverGateMovies("PG", setOf(27, 10749, 53, 80))

        val request = server.requests.single()
        assertEquals("US", request.queryParam("certification_country"))
        assertEquals("PG", request.queryParam("certification.lte"))
        assertEquals("27,10749,53,80", request.queryParam("without_genres"))
        assertEquals("false", request.queryParam("include_adult"))
    }

    @Test
    fun `v4 read access tokens are sent as a Bearer header`() = runTest {
        apiKey = V4_TOKEN
        service.discoverMovies()

        val request = server.requests.single()
        assertEquals("Bearer $V4_TOKEN", request.header("Authorization"))
        assertNull(request.queryParam("api_key"))
    }

    @Test
    fun `provider discover sends the PRD query parameters and parses results`() = runTest {
        val page = service.discoverMovies(StreamingProvider.NETFLIX)

        assertEquals(listOf("Fight Club"), page.results.map { it.title })
        val query = server.requests.single()
        assertEquals("8", query.queryParam("with_watch_providers"))
        assertEquals("US", query.queryParam("watch_region"))
        assertEquals("flatrate", query.queryParam("with_watch_monetization_types"))
        assertEquals("popularity.desc", query.queryParam("sort_by"))
        assertEquals("false", query.queryParam("include_adult"))
        assertEquals("en-US", query.queryParam("language"))
        assertEquals("1", query.queryParam("page"))
    }

    @Test
    fun `paramount plus ORs its US provider ids on movie and tv discover`() = runTest {
        service.discoverMovies(StreamingProvider.PARAMOUNT_PLUS)
        service.discoverTv(StreamingProvider.PARAMOUNT_PLUS)

        assertEquals(listOf("/3/discover/movie", "/3/discover/tv"), server.requests.map { it.path })
        server.requests.forEach { query ->
            assertEquals("2303|531|2616", query.queryParam("with_watch_providers"))
            assertEquals("US", query.queryParam("watch_region"))
            assertEquals("flatrate", query.queryParam("with_watch_monetization_types"))
            assertEquals("popularity.desc", query.queryParam("sort_by"))
            assertEquals("false", query.queryParam("include_adult"))
            assertEquals("en-US", query.queryParam("language"))
            assertEquals("1", query.queryParam("page"))
        }
        assertEquals(531, StreamingProvider.PARAMOUNT_PLUS.tmdbProviderId)
    }

    @Test
    fun `genre discover filters by with_genres without provider parameters`() = runTest {
        service.discoverMovies(genreId = 28, page = 2)

        val query = server.requests.single()
        assertEquals("28", query.queryParam("with_genres"))
        assertEquals("2", query.queryParam("page"))
        assertNull(query.queryParam("with_watch_providers"))
    }

    @Test
    fun `trending movies read the weekly trending list and are disk cached`() = runTest {
        val first = service.trendingMovies()
        service.trendingMovies()

        assertEquals(listOf("Fight Club"), first.results.map { it.title })
        assertEquals(1, server.hits("/3/trending/movie/week"))
        assertEquals("en-US", server.requests.single().queryParam("language"))
    }

    @Test
    fun `movie genre list is parsed in TMDB order`() = runTest {
        val genres = service.genres(MediaType.MOVIE)

        assertEquals(listOf("Action", "Adventure"), genres.map { it.name })
        assertEquals(1, server.hits("/3/genre/movie/list"))
    }

    @Test
    fun `repeat discover calls are served from the 6h disk cache`() = runTest {
        service.discoverMovies()
        service.discoverMovies()

        assertEquals(1, server.hits("/3/discover/movie"))
    }

    @Test
    fun `a 429 from TMDB is retried transparently`() = runTest {
        discoverThrottles.set(2)

        val page = service.discoverMovies()

        assertEquals(1, page.results.size)
        assertEquals(3, server.hits("/3/discover/movie"))
    }

    @Test
    fun `validateApiKey returns true for a working key and false on 401`() = runTest {
        assertTrue(service.validateApiKey(" $V3_KEY "))
        assertTrue(service.validateApiKey(V4_TOKEN))
        assertFalse(service.validateApiKey("wrong-key"))
        assertFalse(service.validateApiKey("   "))
    }

    @Test
    fun `server errors propagate instead of being swallowed`() = runTest {
        assertThrows<ServerResponseException> { service.searchTv("anything") }
    }

    @Test
    fun `calls without a configured key fail fast without network traffic`() = runTest {
        apiKey = null
        assertThrows<TmdbApiKeyMissingException> { service.discoverMovies() }
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun `movie details expose the US theatrical certification`() = runTest {
        val details = service.getMovieDetails(550)

        assertEquals("R", details.usCertification())
        assertEquals(139, details.runtime)
        assertEquals("release_dates,credits,images", server.requests.single().queryParam("append_to_response"))
    }

    @Test
    fun `tv details expose the US content rating`() = runTest {
        val details = service.getTvDetails(1399)

        assertEquals("TV-MA", details.usCertification())
        assertEquals("content_ratings,credits,images", server.requests.single().queryParam("append_to_response"))
    }

    private companion object {
        const val V3_KEY = "0123456789abcdef0123456789abcdef"
        const val V4_TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiJ0ZXN0In0.c2lnbmF0dXJl"

        const val DISCOVER_JSON = """{"page":1,"total_pages":1,"total_results":1,
            "results":[{"id":550,"title":"Fight Club","genre_ids":[18],"vote_average":8.4,"unknown_field":true}]}"""

        const val MOVIE_DETAILS_JSON = """{"id":550,"title":"Fight Club","runtime":139,
            "genres":[{"id":18,"name":"Drama"}],
            "release_dates":{"results":[
              {"iso_3166_1":"DE","release_dates":[{"certification":"18","type":3}]},
              {"iso_3166_1":"US","release_dates":[
                {"certification":"","type":1},
                {"certification":"NR","type":4},
                {"certification":"R","type":3}]}]},
            "credits":{"cast":[{"id":287,"name":"Brad Pitt","character":"Tyler Durden","order":0}]}}"""

        const val TV_DETAILS_JSON = """{"id":1399,"name":"Game of Thrones","number_of_seasons":8,
            "content_ratings":{"results":[{"iso_3166_1":"GB","rating":"18"},{"iso_3166_1":"US","rating":"TV-MA"}]}}"""
    }
}
