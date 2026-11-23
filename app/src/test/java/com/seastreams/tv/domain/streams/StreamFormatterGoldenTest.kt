package com.seastreams.tv.domain.streams

import com.seastreams.tv.domain.model.StreamHints
import com.seastreams.tv.domain.model.StreamSource
import com.seastreams.tv.domain.streams.format.FormatInput
import com.seastreams.tv.domain.streams.format.PredefinedFormatters
import com.seastreams.tv.domain.streams.format.StreamFormatter
import com.seastreams.tv.domain.streams.parse.StreamFacts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `docs/ui-references/aio-formatter.png`: the preview card, rendered from the release name it shows. */
class StreamFormatterGoldenTest {

    private val now = 1_800_000_000_000L
    private val filename = "Movie.Title.2023.2160p.BluRay.HEVC.DV.TrueHD.Atmos.7.1.iTA.ENG-GROUP.mkv"

    private val stream = StreamSource(
        addonUrl = "https://torrentio.example/manifest.json",
        addonName = "Torrentio",
        url = "https://cdn.example/movie.mkv",
        label = "Torrentio\n4k",
        description = filename,
        headers = emptyMap(),
        hints = StreamHints(filename = filename, sizeBytes = 63_000_000_000L, publishedMillis = now - 10 * 24 * 3_600_000L),
    )

    @Test
    fun `the aio-formatter preview renders exactly`() {
        val parsed = StreamFacts.parse(stream, durationMs = 152 * 60_000L, nowMillis = now, usenetAddonUrl = "usenet://builtin")
        val display = StreamFormatter(PredefinedFormatters.CUSTOM_DEFAULT)
            .format(FormatInput(parsed, stream.addonName, stream.addonUrl, ContentKind.MOVIE))

        assertEquals("🔥 4K 🎬 Movie Title (2023)", display.name)
        assertEquals(
            listOf(
                "Movie.Title.2023.2160p.BluRay.HEVC.DV.TrueHD.Atmos.7.1.iTA.ENG-GROUP.mkv",
                "🎥 BluRay 🎞️ HEVC 🔆 DV",
                "🎧 Atmos | TrueHD 🔊 7.1 📦 63 GB ⏱️ 2h:32m:0s 📅 10d",
                "🗣️ 🇬🇧,🇮🇹 | Torrentio",
            ).joinToString("\n"),
            display.description,
        )
    }

    private val usenet = StreamSource(
        addonUrl = "usenet://builtin",
        addonName = "Usenet",
        url = "seastreams-nntp://abc",
        label = "Usenet · 2160p",
        description = "$filename\n63.0 GB · 10 days old · NZBgeek",
        headers = emptyMap(),
        hints = StreamHints(filename = filename, sizeBytes = 63_000_000_000L, publishedMillis = now - 10 * 24 * 3_600_000L, indexer = "NZBgeek"),
    )

    private fun usenetParsed() = StreamFacts.parse(usenet, 152 * 60_000L, now, usenetAddonUrl = "usenet://builtin")

    @Test
    fun `a Usenet stream shows its indexer instead of Usenet`() {
        val display = StreamFormatter(PredefinedFormatters.CUSTOM_DEFAULT)
            .format(FormatInput(usenetParsed(), usenet.addonName, usenet.addonUrl, ContentKind.MOVIE))
        assertEquals("🗣️ 🇬🇧,🇮🇹 | NZBgeek", display.description.lines().last())
        assertEquals("NZBgeek", usenetParsed().indexer)
    }

    @Test
    fun `predefined formatters name the Usenet indexer`() {
        val input = FormatInput(usenetParsed(), usenet.addonName, usenet.addonUrl, ContentKind.MOVIE)
        assertEquals("NZBgeek 2160p", StreamFormatter(PredefinedFormatters.TORRENTIO).format(input).name.lines().first())
        listOf(PredefinedFormatters.GDRIVE, PredefinedFormatters.LIGHT_GDRIVE, PredefinedFormatters.PRISM, PredefinedFormatters.MINIMALISTIC_GDRIVE).forEach {
            val display = StreamFormatter(it).format(input)
            assertTrue("NZBgeek" in display.name + display.description, display.toString())
            assertFalse("Usenet" in display.name, display.name)
        }
    }

    @Test
    fun `a Stremio addon that states an indexer shows both`() {
        val withIndexer = stream.copy(description = "$filename\n👤 12 ⚙️ ThePirateBay")
        val parsed = StreamFacts.parse(withIndexer, 152 * 60_000L, now, usenetAddonUrl = "usenet://builtin")
        val display = StreamFormatter(PredefinedFormatters.CUSTOM_DEFAULT)
            .format(FormatInput(parsed, withIndexer.addonName, withIndexer.addonUrl, ContentKind.MOVIE))
        assertEquals("🗣️ 🇬🇧,🇮🇹 | Torrentio · ThePirateBay", display.description.lines().last())
    }

    @Test
    fun `the pipeline formats with the Custom default and keeps the raw text`() {
        val settings = StreamProcessingSettings(formatter = StreamFormatterChoice(FormatterId.CUSTOM))
        val out = StreamPipeline.process(listOf(stream), settings, StreamContext(ContentKind.MOVIE, runtimeMinutes = 152), now).single()

        assertEquals("🔥 4K 🎬 Movie Title (2023)", out.display?.name)
        assertEquals(stream.label, out.label)
        assertEquals(stream.description, out.description)
    }
}
