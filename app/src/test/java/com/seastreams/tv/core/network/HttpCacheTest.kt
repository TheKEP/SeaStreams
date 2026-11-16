package com.seastreams.tv.core.network

import com.seastreams.tv.testutil.TestHttpServer
import com.seastreams.tv.testutil.dateAgo
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Exercises the real OkHttp disk cache + [CacheTtlInterceptor] against a local HTTP server.
 *
 * The server stamps each response with a `Date` header in the past, which is how HTTP caches
 * measure an entry's age. That lets us assert 6h/12h freshness boundaries without waiting hours.
 */
class HttpCacheTest {

    @TempDir
    lateinit var cacheDir: File

    /** Age the server reports for each path; absent → generated "now". */
    private val responseAge = ConcurrentHashMap<String, Duration>()

    private lateinit var server: TestHttpServer
    private lateinit var cache: Cache
    private lateinit var client: OkHttpClient

    @BeforeEach
    fun setUp() {
        server = TestHttpServer {
            get("/3/discover/movie") { respondJson(call, "/3/discover/movie", originCacheControl = "public, max-age=300") }
            get("/3/movie/550/external_ids") { respondJson(call, "/3/movie/550/external_ids") }
            get("/3/configuration") { respondJson(call, "/3/configuration") }
            get("/3/search/movie") { respondJson(call, "/3/search/movie", originCacheControl = "no-store") }
            get("/3/movie/999") { call.respondText("""{"status_code":34}""", ContentType.Application.Json, HttpStatusCode.NotFound) }
            get("/mdb/lists/official/movies/top/json") {
                call.response.header("Vary", "Cookie")
                respondJson(call, "/mdb/lists/official/movies/top/json", originCacheControl = "max-age=86400")
            }
            get("/addon/stream/movie/tt1.json") {
                respondJson(call, "/addon/stream/movie/tt1.json", originCacheControl = "no-store")
            }
        }
        cache = Cache(cacheDir, CACHE_SIZE)
        client = newClient(cache)
    }

    @AfterEach
    fun tearDown() {
        cache.close()
        server.close()
    }

    @Nested
    inner class CatalogTtlSixHours {

        @Test
        fun `second discover request is served from disk cache without hitting the network`() {
            val first = fetch("/3/discover/movie")
            val second = fetch("/3/discover/movie")

            assertNotNull(first.networkResponse)
            assertNull(first.cacheResponse)
            assertNotNull(second.cacheResponse, "expected cache hit")
            assertNull(second.networkResponse, "expected no network call")
            assertEquals(1, server.hits("/3/discover/movie"))
        }

        @Test
        fun `discover entry aged 5h50m is still a cache hit`() {
            responseAge["/3/discover/movie"] = 5.hours + 50.minutes
            fetch("/3/discover/movie")
            val second = fetch("/3/discover/movie")

            assertNotNull(second.cacheResponse)
            assertEquals(1, server.hits("/3/discover/movie"))
        }

        @Test
        fun `discover entry aged 6h10m is a cache miss and is refetched`() {
            responseAge["/3/discover/movie"] = 6.hours + 10.minutes
            fetch("/3/discover/movie")
            val second = fetch("/3/discover/movie")

            assertNotNull(second.networkResponse)
            assertEquals(2, server.hits("/3/discover/movie"))
        }

        @Test
        fun `origin no-store is overridden for TMDB responses`() {
            fetch("/3/search/movie")
            val second = fetch("/3/search/movie")

            assertNotNull(second.cacheResponse)
            assertEquals(1, server.hits("/3/search/movie"))
            assertEquals("public, max-age=21600", second.header("Cache-Control"))
        }

        @Test
        fun `MDBList 24h origin max-age is shortened to 6h and Vary Cookie is dropped`() {
            val path = "/mdb/lists/official/movies/top/json"
            responseAge[path] = 6.hours + 10.minutes
            fetch(path)
            fetch(path)
            assertEquals(2, server.hits(path), "a 24h origin TTL would have produced a hit")

            responseAge[path] = 5.hours
            fetch(path)
            val cached = fetch(path)
            assertNotNull(cached.cacheResponse)
            assertNull(cached.header("Vary"))
            assertEquals(3, server.hits(path))
        }
    }

    @Nested
    inner class StableMetadataTtlTwelveHours {

        @Test
        fun `external_ids entry aged 11h50m is a cache hit`() {
            responseAge["/3/movie/550/external_ids"] = 11.hours + 50.minutes
            fetch("/3/movie/550/external_ids")
            val second = fetch("/3/movie/550/external_ids")

            assertNotNull(second.cacheResponse)
            assertEquals(1, server.hits("/3/movie/550/external_ids"))
        }

        @Test
        fun `external_ids entry aged 12h10m is a cache miss`() {
            responseAge["/3/movie/550/external_ids"] = 12.hours + 10.minutes
            fetch("/3/movie/550/external_ids")
            val second = fetch("/3/movie/550/external_ids")

            assertNotNull(second.networkResponse)
            assertEquals(2, server.hits("/3/movie/550/external_ids"))
        }

        @Test
        fun `entries survive a new client instance because the cache lives on disk`() {
            fetch("/3/movie/550/external_ids")
            cache.close()

            cache = Cache(cacheDir, CACHE_SIZE)
            client = newClient(cache)
            val afterRestart = fetch("/3/movie/550/external_ids")

            assertNotNull(afterRestart.cacheResponse)
            assertEquals(1, server.hits("/3/movie/550/external_ids"))
        }
    }

    @Nested
    inner class NotCached {

        @Test
        fun `configuration key-validation call is never cached`() {
            fetch("/3/configuration")
            fetch("/3/configuration")
            assertEquals(2, server.hits("/3/configuration"))
        }

        @Test
        fun `error responses are not cached`() {
            fetch("/3/movie/999")
            fetch("/3/movie/999")
            assertEquals(2, server.hits("/3/movie/999"))
        }

        @Test
        fun `non TMDB or MDBList hosts keep their origin no-store`() {
            fetch("/addon/stream/movie/tt1.json")
            fetch("/addon/stream/movie/tt1.json")
            assertEquals(2, server.hits("/addon/stream/movie/tt1.json"))
        }
    }

    private suspend fun respondJson(
        call: io.ktor.server.application.ApplicationCall,
        path: String,
        originCacheControl: String? = null,
    ) {
        responseAge[path]?.let { call.response.dateAgo(it) }
        originCacheControl?.let { call.response.header("Cache-Control", it) }
        call.respondText("""{"ok":true}""", ContentType.Application.Json)
    }

    private fun newClient(cache: Cache): OkHttpClient {
        val endpoints = ApiEndpoints(tmdbBaseUrl = server.url("/3"), mdbListBaseUrl = server.url("/mdb"))
        return NetworkModule.buildOkHttpClient(
            cache = cache,
            cachePolicy = HttpCachePolicy(endpoints),
            retryInterceptor = RateLimitRetryInterceptor(sleeper = { }),
        )
    }

    /** Executes a GET, fully consumes the body (so it is committed to cache) and returns the response. */
    private fun fetch(path: String): Response {
        val response = client.newCall(Request.Builder().url(server.url(path)).build()).execute()
        response.use { it.body.string() }
        return response
    }

    private companion object {
        const val CACHE_SIZE = 10L * 1024 * 1024
    }
}
