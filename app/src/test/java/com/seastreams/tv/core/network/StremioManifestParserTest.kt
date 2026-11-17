package com.seastreams.tv.core.network

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class StremioManifestParserTest {

    @Test
    fun `parses a manifest with string resources`() {
        val manifest = StremioManifestParser.parse(
            """
            {"id":"org.stremio.opensubtitlesv3","version":"1.0.0","name":"OpenSubtitles v3",
             "description":"Subtitles","resources":["subtitles"],"types":["movie","series"],
             "idPrefixes":["tt"],"catalogs":[],"behaviorHints":{"configurable":false}}
            """,
        )

        assertEquals("org.stremio.opensubtitlesv3", manifest.id)
        assertEquals("1.0.0", manifest.version)
        assertEquals("OpenSubtitles v3", manifest.name)
        assertEquals(listOf("movie", "series"), manifest.types)
        assertEquals(listOf("tt"), manifest.idPrefixes)
        assertEquals(listOf(StremioResource("subtitles", emptyList(), emptyList())), manifest.resources)
        assertTrue(manifest.supports("subtitles", "series"))
        assertFalse(manifest.supports("stream", "movie"))
        assertNull(manifest.logo)
    }

    @Test
    fun `parses object resources with their own types and prefixes, plus catalogs`() {
        val manifest = StremioManifestParser.parse(
            """
            {"id":"com.example.streams","version":"2.3.1","name":"Example","logo":"https://x/logo.png",
             "types":["movie","series","channel"],
             "resources":["catalog",{"name":"stream","types":["movie"],"idPrefixes":["tt","kitsu"]},{"bogus":1},42],
             "catalogs":[{"type":"movie","id":"top","name":"Top"},{"type":"series","id":"new"},{"id":"broken"}]}
            """,
        )

        assertEquals(
            listOf(
                StremioResource("catalog", emptyList(), emptyList()),
                StremioResource("stream", listOf("movie"), listOf("tt", "kitsu")),
            ),
            manifest.resources,
        )
        assertTrue(manifest.supports("stream", "movie"))
        assertFalse(manifest.supports("stream", "series"), "object resource narrows types")
        assertTrue(manifest.supports("catalog", "channel"), "string resource inherits manifest types")
        assertEquals(listOf(StremioCatalog("movie", "top", "Top"), StremioCatalog("series", "new", "new")), manifest.catalogs)
        assertEquals("https://x/logo.png", manifest.logo)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            """{"version":"1.0.0","name":"No id","resources":["stream"]}""",
            """{"id":"x","version":"1.0.0","resources":["stream"]}""",
            """{"id":"x","name":"No version","resources":["stream"]}""",
            """{"id":"x","version":"1.0.0","name":"No resources"}""",
            """{"id":"x","version":"1.0.0","name":"Empty","resources":[]}""",
            """{"id":"  ","version":"1.0.0","name":"Blank id","resources":["stream"]}""",
            """["not","an","object"]""",
            """<html>Not found</html>""",
        ],
    )
    fun `rejects unusable manifests`(raw: String) {
        assertThrows<InvalidManifestException> { StremioManifestParser.parse(raw) }
    }

    @ParameterizedTest
    @CsvSource(
        "stremio://torrentio.strem.fun/manifest.json, https://torrentio.strem.fun/manifest.json",
        "https://addon.example.com/cfg=abc/manifest.json, https://addon.example.com/cfg=abc/manifest.json",
        "https://addon.example.com/, https://addon.example.com/manifest.json",
        "addon.example.com/path, https://addon.example.com/path/manifest.json",
        "http://192.168.1.5:11470/manifest.json#frag, http://192.168.1.5:11470/manifest.json",
    )
    fun `normalises addon URLs`(input: String, expected: String) {
        assertEquals(expected, StremioAddonUrl.normalize(input))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   ", "ftp://example.com/manifest.json", "not a url", "javascript://alert(1)"])
    fun `rejects non-addon URLs`(input: String) {
        assertNull(StremioAddonUrl.normalize(input))
    }

    @Test
    fun `transport base strips manifest json`() {
        assertEquals(
            "https://addon.example.com/cfg=abc",
            StremioAddonUrl.transportBase("https://addon.example.com/cfg=abc/manifest.json"),
        )
    }

    @Test
    fun `catalogs that need an extra parameter are flagged`() {
        val manifest = StremioManifestParser.parse(
            """{"id":"a","version":"1","name":"A","resources":["catalog"],"catalogs":[
                {"type":"movie","id":"top","name":"Top","extra":[{"name":"skip"},{"name":"genre","isRequired":false}]},
                {"type":"movie","id":"search","extra":[{"name":"search","isRequired":true}]},
                {"type":"series","id":"legacy","extraRequired":["genre"]}
            ]}""",
        )

        assertEquals(listOf(false, true, true), manifest.catalogs.map { it.requiresExtra })
    }

    @Test
    fun `parses catalog metas leniently`() {
        val metas = StremioResourceParser.parseMetas(
            """{"metas":[{"id":"tt0133093","type":"movie","name":"The Matrix"},{"type":"movie"},42,{"id":"tmdb:603"}]}""",
        )

        assertEquals(listOf(StremioMetaPreview("tt0133093", "movie"), StremioMetaPreview("tmdb:603", "")), metas)
        assertTrue(StremioResourceParser.parseMetas("not json").isEmpty())
    }

    @Test
    fun `catalog URLs page with the skip extra and keep the addon configuration`() {
        val manifestUrl = "https://addon.example.com/cfg=abc/manifest.json?token=1"

        assertEquals(
            "https://addon.example.com/cfg=abc/catalog/movie/top.json?token=1",
            StremioAddonClient.catalogUrl(manifestUrl, "movie", "top", skip = 0),
        )
        assertEquals(
            "https://addon.example.com/cfg=abc/catalog/series/top%20rated/skip=100.json?token=1",
            StremioAddonClient.catalogUrl(manifestUrl, "series", "top rated", skip = 100),
        )
    }
}
