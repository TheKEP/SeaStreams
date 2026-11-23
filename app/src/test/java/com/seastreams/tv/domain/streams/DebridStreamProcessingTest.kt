package com.seastreams.tv.domain.streams

import com.seastreams.tv.data.debrid.library.DebridLibrarySearch
import com.seastreams.tv.domain.model.StreamHints
import com.seastreams.tv.domain.model.StreamSource
import com.seastreams.tv.domain.streams.parse.StreamFacts
import com.seastreams.tv.domain.streams.parse.StreamKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PRD §13 / §14: Debrid library streams go through the same filters, dedup, sorting, limits and formatter as Stremio
 * and Usenet streams. Their type is `Debrid` and the formatters show the service where they'd show the addon.
 */
class DebridStreamProcessingTest {

    private fun debrid(filename: String, service: String, size: Long, token: Char) = StreamSource(
        addonUrl = DebridLibrarySearch.DEBRID_ADDON_URL,
        addonName = DebridLibrarySearch.DEBRID_ADDON_NAME,
        url = "seastreams-debrid://" + token.toString().repeat(32),
        label = "$service · 1080p",
        description = "$filename\n$service",
        headers = emptyMap(),
        hints = StreamHints(filename = filename, sizeBytes = size, indexer = service),
    )

    private val rd4k = debrid("Inception.2010.2160p.UHD.BluRay.REMUX.HDR.mkv", "Real-Debrid", 60 * GB, 'a')
    private val tb1080 = debrid("Inception.2010.1080p.WEB-DL.x264.mkv", "TorBox", 8 * GB, 'b')
    private val rd720 = debrid("Inception.2010.720p.BluRay.x264.mkv", "Real-Debrid", 4 * GB, 'c')
    private val torrentio = stream("Inception.2010.1080p.BluRay.x265", addon = "torrentio", sizeBytes = 12 * GB)
    private val all = listOf(rd4k, tb1080, rd720, torrentio)
    private val context = StreamContext(ContentKind.MOVIE, runtimeMinutes = 148, title = "Inception", year = 2010, addonOrder = listOf("https://torrentio/manifest.json", DebridLibrarySearch.DEBRID_ADDON_URL))

    private fun process(settings: StreamProcessingSettings) = StreamPipeline.process(all, settings, context, nowMillis = 0)

    @Test
    fun `a built-in Debrid stream is type Debrid with the service as its indexer, whatever its text`() {
        val parsed = StreamFacts.parse(rd4k.copy(label = "plain", description = "Inception.2010.2160p.mkv"), null, 0, USENET_URL)

        assertEquals(StreamKind.DEBRID, parsed.type)
        assertEquals("Real-Debrid", parsed.indexer)
        assertEquals(60 * GB, parsed.sizeBytes)
        assertEquals("2160p", parsed.release.resolution)
    }

    @Test
    fun `filters apply to Debrid streams like any other`() {
        val noDebrid = process(StreamProcessingSettings(StreamFilters(streamTypes = AttributeFilter(excluded = listOf("Debrid")))))
        assertEquals(listOf(torrentio.url), noDebrid.map { it.url })

        val only1080 = process(StreamProcessingSettings(StreamFilters(resolutions = AttributeFilter(required = listOf("1080p")))))
        assertEquals(listOf(tb1080.url, torrentio.url), only1080.map { it.url })
    }

    @Test
    fun `sorting and per-addon limits mix Debrid and Stremio streams`() {
        val bySize = process(StreamProcessingSettings(sort = StreamSort(global = listOf(SortCriterion(SortKey.SIZE)))))
        assertEquals(listOf(rd4k.url, torrentio.url, tb1080.url, rd720.url), bySize.map { it.url })

        val byAddon = process(StreamProcessingSettings(sort = StreamSort(global = listOf(SortCriterion(SortKey.ADDON), SortCriterion(SortKey.SIZE)))))
        assertEquals(listOf(torrentio.url, rd4k.url, tb1080.url, rd720.url), byAddon.map { it.url })

        val limited = process(StreamProcessingSettings(StreamFilters(limits = ResultLimits(perAddon = 2))))
        assertEquals(2, limited.count { it.addonUrl == DebridLibrarySearch.DEBRID_ADDON_URL })
    }

    @Test
    fun `predefined formatters show the service where they'd show the addon`() {
        for (id in listOf(FormatterId.TORRENTIO, FormatterId.GDRIVE, FormatterId.LIGHT_GDRIVE, FormatterId.PRISM)) {
            val formatted = process(StreamProcessingSettings(formatter = StreamFormatterChoice(id)))
            val text = formatted.first { it.url == tb1080.url }.display.let { "${it?.name}\n${it?.description}" }
            assertTrue("TorBox" in text, "$id: $text")
            assertTrue(Regex("(?<![A-Za-z-])Debrid(?![A-Za-z-])").find(text.replace("Real-Debrid", "")) == null, "$id names the card, not the service: $text")
            val other = formatted.first { it.url == torrentio.url }.display
            assertTrue("torrentio" in "${other?.name}\n${other?.description}", "$id keeps a Stremio addon's name")
        }
    }

    @Test
    fun `the Custom default ends with the service, and Prism marks library files`() {
        val custom = process(StreamProcessingSettings(formatter = StreamFormatterChoice(FormatterId.CUSTOM)))
        assertTrue(custom.first { it.url == rd4k.url }.display?.description.orEmpty().trimEnd().endsWith("| Real-Debrid"))

        val prism = process(StreamProcessingSettings(formatter = StreamFormatterChoice(FormatterId.PRISM)))
        assertTrue("📌 Library" in prism.first { it.url == rd720.url }.display?.description.orEmpty())
        assertTrue("📌 Library" !in prism.first { it.url == torrentio.url }.display?.description.orEmpty())
    }
}
