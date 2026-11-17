package com.seastreams.tv.core.network

import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class HttpCachePolicyTest {

    private val policy = HttpCachePolicy(ApiEndpoints())

    @ParameterizedTest(name = "{0} -> {1}h")
    @CsvSource(
        "https://api.themoviedb.org/3/discover/movie?with_watch_providers=8&page=1, 6",
        "https://api.themoviedb.org/3/discover/tv?page=2, 6",
        "https://api.themoviedb.org/3/search/movie?query=alien, 6",
        "https://api.themoviedb.org/3/movie/550/external_ids, 12",
        "https://api.themoviedb.org/3/tv/1399/external_ids, 12",
        "https://api.themoviedb.org/3/movie/550?append_to_response=credits, 12",
        "https://api.themoviedb.org/3/tv/1399/season/1, 12",
        "https://api.themoviedb.org/3/find/tt0133093?external_source=imdb_id, 12",
        "https://mdblist.com/lists/official/movies/most-watched-week/json, 6",
    )
    fun `assigns the PRD TTL per endpoint`(url: String, expectedHours: Int) {
        assertEquals(expectedHours.hours, policy.ttlFor(get(url)))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://api.themoviedb.org/3/configuration",
            "https://mdblist.com/lists/official/movies/most-watched-week",
            "https://image.tmdb.org/t/p/w500/poster.jpg",
            "https://v3-cinemeta.strem.io/manifest.json",
            "https://api.themoviedb.org/4/list/1",
        ],
    )
    fun `does not override caching for other URLs`(url: String) {
        assertNull(policy.ttlFor(get(url)))
    }

    @Test
    fun `only GET requests are cached`() {
        val post = Request.Builder()
            .url("https://api.themoviedb.org/3/discover/movie")
            .post("{}".toRequestBody())
            .build()
        assertNull(policy.ttlFor(post))
    }

    @Test
    fun `TTLs outside the 6 to 12 hour window are rejected`() {
        assertThrows<IllegalArgumentException> { HttpCachePolicy(ApiEndpoints(), catalogTtl = 5.hours + 59.minutes) }
        assertThrows<IllegalArgumentException> { HttpCachePolicy(ApiEndpoints(), stableMetadataTtl = 12.hours + 1.minutes) }
        assertDoesNotThrow { HttpCachePolicy(ApiEndpoints(), catalogTtl = 6.hours, stableMetadataTtl = 12.hours) }
    }

    private fun get(url: String) = Request.Builder().url(url).build()
}
