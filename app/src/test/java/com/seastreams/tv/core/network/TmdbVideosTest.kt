package com.seastreams.tv.core.network

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** PRD §9 Trailer Candidates: how TMDB `/videos` results are ranked into YouTube keys to try. */
class TmdbVideosTest {

    private fun yt(key: String, type: String = "Trailer", official: Boolean = true, published: String = "", site: String = "YouTube") =
        TmdbVideo(key = key, site = site, type = type, official = official, publishedAt = published)

    @Test
    fun `official trailers come first, newest first, then other trailers, then teasers`() {
        val videos = TmdbVideos(
            listOf(
                yt("teaser", type = "Teaser", published = "2026-08-27T13:00:37.000Z"),
                yt("unofficial", official = false, published = "2026-09-01T00:00:00.000Z"),
                yt("official-old", published = "2026-02-19T17:00:14.000Z"),
                yt("featurette", type = "Featurette", published = "2026-10-01T00:00:00.000Z"),
                yt("official-new", published = "2026-05-26T16:10:05.000Z"),
            ),
        )
        assertEquals(listOf("official-new", "official-old", "unofficial"), videos.youtubeTrailerKeys())
    }

    @Test
    fun `teasers fill in only after every trailer, capped at 3 candidates`() {
        val videos = TmdbVideos(listOf(yt("t1"), yt("s1", type = "Teaser"), yt("s2", type = "Teaser"), yt("s3", type = "Teaser")))
        assertEquals(listOf("t1", "s1", "s2"), videos.youtubeTrailerKeys())
        assertEquals(MAX_TRAILER_CANDIDATES, videos.youtubeTrailerKeys().size)
    }

    @Test
    fun `a teaser-only title still gets candidates`() {
        val videos = TmdbVideos(listOf(yt("s1", type = "Teaser", published = "2025-11-11T20:00:13.000Z")))
        assertEquals(listOf("s1"), videos.youtubeTrailerKeys())
    }

    @Test
    fun `undated or unparseable dates sort after dated ones within a tier`() {
        val videos = TmdbVideos(
            listOf(yt("blank"), yt("garbage", published = "not-a-date"), yt("dated", published = "2020-01-01T00:00:00.000Z")),
        )
        assertEquals("dated", videos.youtubeTrailerKeys().first())
        assertEquals(setOf("blank", "garbage"), videos.youtubeTrailerKeys().drop(1).toSet())
    }

    @Test
    fun `non-YouTube sites, blank keys and duplicate keys are dropped`() {
        val videos = TmdbVideos(
            listOf(yt("vimeo", site = "Vimeo"), yt(""), yt("dup", published = "2026-01-02T00:00:00Z"), yt("dup", type = "Teaser"), yt("other", site = "youtube")),
        )
        assertEquals(listOf("dup", "other"), videos.youtubeTrailerKeys())
    }

    @Test
    fun `nothing usable gives an empty list`() {
        assertTrue(TmdbVideos(listOf(yt("clip", type = "Clip"))).youtubeTrailerKeys().isEmpty())
        assertTrue(TmdbVideos().youtubeTrailerKeys().isEmpty())
    }

    @Test
    fun `published_at is decoded from TMDB JSON`() {
        val json = """{"results":[{"key":"c51ND9Hdbw0","site":"YouTube","type":"Trailer","official":true,"published_at":"2026-02-19T17:00:14.000Z","name":"Official Trailer","size":2160}]}"""
        val videos = Json { ignoreUnknownKeys = true }.decodeFromString(TmdbVideos.serializer(), json)
        assertEquals("2026-02-19T17:00:14.000Z", videos.results.single().publishedAt)
        assertEquals(listOf("c51ND9Hdbw0"), videos.youtubeTrailerKeys())
    }
}
