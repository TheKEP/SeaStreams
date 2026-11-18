package com.seastreams.tv.domain.usecase

import com.seastreams.tv.core.network.StremioManifestParser
import com.seastreams.tv.core.network.StremioResourceParser
import com.seastreams.tv.domain.model.EpisodeRef
import com.seastreams.tv.domain.model.MediaRef
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.StreamSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StreamResolutionTest {

    @Test
    fun `only direct url streams are kept, with labels and request headers`() {
        val raw = """
            {"streams":[
              {"name":"Addon\n4k","description":"Movie.2160p.mkv\n12 GB","url":"https://cdn.example/a.mkv",
               "behaviorHints":{"proxyHeaders":{"request":{"Referer":"https://example"}}}},
              {"name":"Torrent","infoHash":"abc123"},
              {"title":"Old style title","url":"http://cdn.example/b.mp4"},
              {"name":"Browser only","externalUrl":"https://example/watch"}
            ]}
        """.trimIndent()

        val streams = StremioResourceParser.parseStreams(raw)

        assertEquals(listOf("https://cdn.example/a.mkv", "http://cdn.example/b.mp4"), streams.map { it.url })
        assertEquals("Addon\n4k", streams[0].name)
        assertEquals(mapOf("Referer" to "https://example"), streams[0].headers)
        assertEquals("Old style title", streams[1].description)
    }

    @Test
    fun `malformed responses yield no streams instead of failing`() {
        assertTrue(StremioResourceParser.parseStreams("not json").isEmpty())
        assertTrue(StremioResourceParser.parseStreams("""{"streams":"nope"}""").isEmpty())
        assertTrue(StremioResourceParser.parseSubtitles("""{"subtitles":[{"lang":"eng"}]}""").isEmpty())
    }

    @Test
    fun `subtitles keep url and language`() {
        val subs = StremioResourceParser.parseSubtitles("""{"subtitles":[{"id":"1","url":"https://s/1.srt","lang":"eng"}]}""")
        assertEquals("eng", subs.single().language)
    }

    private val movie = StreamQuery(1, MediaRef(603, MediaType.MOVIE), imdbId = "tt0133093", episode = null)
    private val episode = StreamQuery(1, MediaRef(1399, MediaType.TV), imdbId = "tt0944947", episode = EpisodeRef(2, 5))

    private fun manifest(resources: String, idPrefixes: String = "[]", types: String = """["movie","series"]""") =
        StremioManifestParser.parse("""{"id":"x","name":"X","version":"1","types":$types,"idPrefixes":$idPrefixes,"resources":$resources}""")

    @Test
    fun `IMDb ids are used for addons that accept tt ids or declare no prefixes`() {
        assertEquals("tt0133093", stremioId(manifest("""["stream"]""", """["tt"]"""), "stream", "movie", movie))
        assertEquals("tt0133093", stremioId(manifest("""["stream"]"""), "stream", "movie", movie))
    }

    @Test
    fun `episodes append season and episode`() {
        assertEquals("tt0944947:2:5", stremioId(manifest("""["stream"]""", """["tt"]"""), "stream", "series", episode))
    }

    @Test
    fun `tmdb ids are used when an addon only accepts them`() {
        assertEquals("tmdb:603", stremioId(manifest("""["stream"]""", """["tmdb:"]"""), "stream", "movie", movie))
        assertEquals("tmdb:1399:2:5", stremioId(manifest("""["stream"]""", """["tmdb:"]"""), "stream", "series", episode))
    }

    @Test
    fun `addons that don't serve the resource or type are skipped`() {
        assertNull(stremioId(manifest("""["subtitles"]"""), "stream", "movie", movie))
        assertNull(stremioId(manifest("""["stream"]""", types = """["series"]"""), "stream", "movie", movie))
        assertNull(stremioId(manifest("""["stream"]""", """["kitsu:"]"""), "stream", "movie", movie))
    }

    @Test
    fun `resource-level types and prefixes override the manifest's`() {
        val m = manifest("""[{"name":"stream","types":["movie"],"idPrefixes":["tmdb:"]}]""", """["tt"]""")
        assertEquals("tmdb:603", stremioId(m, "stream", "movie", movie))
        assertNull(stremioId(m, "stream", "series", episode))
    }

    @Test
    fun `top stream comes from the preferred addon when it has one`() {
        val a = stream("https://a/manifest.json", "1")
        val b = stream("https://b/manifest.json", "2")
        assertEquals(b, topStream(listOf(a, b), "https://b/manifest.json"))
        assertEquals(a, topStream(listOf(a, b), "https://missing/manifest.json"))
        assertEquals(a, topStream(listOf(a, b), null))
        assertNull(topStream(emptyList(), null))
    }

    @Test
    fun `subtitle languages match across ISO 639-1 and 639-2 codes`() {
        assertTrue(subtitleLanguageMatches("eng", "en"))
        assertTrue(subtitleLanguageMatches("en", "en"))
        assertTrue(subtitleLanguageMatches("fre", "fr"))
        assertTrue(subtitleLanguageMatches("fra", "fr"))
        assertFalse(subtitleLanguageMatches("spa", "en"))
        assertFalse(subtitleLanguageMatches("", "en"))
    }

    private fun stream(addon: String, id: String) = StreamSource(addon, "Addon", "https://cdn/$id", id, "", emptyMap())
}
