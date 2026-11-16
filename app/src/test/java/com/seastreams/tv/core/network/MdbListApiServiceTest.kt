package com.seastreams.tv.core.network

import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.testutil.TestHttpServer
import io.ktor.client.HttpClient
import io.ktor.http.ContentType
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
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.File

class MdbListApiServiceTest {

    @Nested
    inner class UrlNormalisation {

        @ParameterizedTest(name = "{0}")
        @CsvSource(
            "https://mdblist.com/lists/official/movies/most-watched-week",
            "https://mdblist.com/lists/official/movies/most-watched-week/",
            "https://mdblist.com/lists/official/movies/most-watched-week/json",
            "https://mdblist.com/lists/official/movies/most-watched-week?sort=rank#top",
            "https://www.mdblist.com/lists/official/movies/most-watched-week",
            "http://mdblist.com/lists/official/movies/most-watched-week",
            "mdblist.com/lists/official/movies/most-watched-week",
            "'  https://mdblist.com/lists/official/movies/most-watched-week  '",
        )
        fun `appends json to the canonical list URL`(input: String) {
            assertEquals(
                "https://mdblist.com/lists/official/movies/most-watched-week/json",
                MdbListUrl.toJsonUrl(input),
            )
        }

        @Test
        fun `user lists with two path segments are supported`() {
            assertEquals(
                "https://mdblist.com/lists/snoak/trending-kids-movies/json",
                MdbListUrl.toJsonUrl("https://mdblist.com/lists/snoak/trending-kids-movies"),
            )
        }

        @ParameterizedTest(name = "rejects \"{0}\"")
        @ValueSource(
            strings = [
                "",
                "   ",
                "https://example.com/lists/snoak/trending-kids-movies",
                "https://mdblist.com.evil.io/lists/a/b",
                "https://mdblist.com/lists/snoak",
                "https://mdblist.com/movie/tt0137523",
                "not a url at all",
            ],
        )
        fun `rejects links that are not MDBList lists`(input: String) {
            assertNull(MdbListUrl.toJsonUrl(input))
        }
    }

    @Nested
    inner class Fetching {

        @TempDir
        lateinit var cacheDir: File

        private lateinit var server: TestHttpServer
        private lateinit var cache: Cache
        private lateinit var http: HttpClient
        private lateinit var service: MdbListApiService

        @BeforeEach
        fun setUp() {
            server = TestHttpServer {
                get("/lists/official/movies/most-watched-week/json") {
                    // Deliberately text/plain: parsing must not depend on Content-Type.
                    call.respondText(LIST_JSON, ContentType.Text.Plain)
                }
            }
            val endpoints = ApiEndpoints(tmdbBaseUrl = server.url("/3"), mdbListBaseUrl = server.baseUrl)
            val json = NetworkModule.provideJson()
            cache = Cache(cacheDir, 10L * 1024 * 1024)
            val okHttp = NetworkModule.buildOkHttpClient(cache, HttpCachePolicy(endpoints), RateLimitRetryInterceptor(sleeper = { }))
            http = NetworkModule.buildKtorClient(okHttp, json)
            service = MdbListApiService(http, endpoints, json)
        }

        @AfterEach
        fun tearDown() {
            http.close()
            cache.close()
            server.close()
        }

        @Test
        fun `fetches the json export and parses movies and shows`() = runTest {
            val items = service.fetchList("https://mdblist.com/lists/official/movies/most-watched-week")

            assertEquals(listOf("/lists/official/movies/most-watched-week/json"), server.requests.map { it.path })
            assertEquals(3, items.size)

            val movie = items[0]
            assertEquals(1368337, movie.tmdbId)
            assertEquals("tt33764258", movie.imdbId)
            assertEquals(362547, movie.tvdbId)
            assertEquals(MediaType.MOVIE, movie.mediaType)
            assertEquals(2026, movie.releaseYear)
            assertFalse(movie.isAdult)

            val show = items[1]
            assertEquals(MediaType.TV, show.mediaType)
            assertEquals(81189, show.tvdbId, "tvdb_id spelling is accepted too")

            assertTrue(items[2].isAdult)
            assertNull(items[2].mediaType)
        }

        @Test
        fun `repeat fetches are served from the 6h cache`() = runTest {
            service.fetchList("https://mdblist.com/lists/official/movies/most-watched-week")
            service.fetchList("https://mdblist.com/lists/official/movies/most-watched-week/")

            assertEquals(1, server.requests.size)
        }

        @Test
        fun `invalid links fail without any network request`() = runTest {
            assertThrows<IllegalArgumentException> { service.fetchList("https://example.com/lists/a/b") }
            assertTrue(server.requests.isEmpty())
        }
    }

    private companion object {
        const val LIST_JSON = """[
            {"id": 1368337, "rank": 0, "adult": 0, "title": "The Odyssey", "tvdbid": 362547,
             "imdb_id": "tt33764258", "mediatype": "movie", "release_year": 2026, "extra": [1, 2]},
            {"id": 1396, "rank": 1, "adult": 0, "title": "Breaking Bad", "tvdb_id": 81189,
             "imdb_id": "tt0903747", "mediatype": "show", "release_year": 2008},
            {"id": 42, "rank": 2, "adult": 1, "title": "Flagged", "mediatype": "person"}
        ]"""
    }
}
