package com.seastreams.tv.domain.streams

import com.seastreams.tv.domain.playback.LastUsedStream
import com.seastreams.tv.domain.playback.lastUsedIndex
import com.seastreams.tv.domain.usecase.topStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class StreamPipelineTest {

    private val streams = listOf(
        stream("Film.2020.480p.DVDRip.XviD-A", addon = "a", label = "A · 480p"),
        stream("Film.2020.720p.WEB-DL.x264-A", addon = "a", label = "A · 720p"),
        stream("Film.2020.1080p.BluRay.x264-B", addon = "b", label = "B · 1080p"),
        stream("Film.2020.2160p.WEB-DL.HEVC-B", addon = "b", label = "B · 4k"),
    )
    private val context = StreamContext(ContentKind.MOVIE, addonOrder = listOf("https://a/manifest.json", "https://b/manifest.json"))

    private val settings = StreamProcessingSettings(
        filters = StreamFilters(resolutions = AttributeFilter(excluded = listOf("480p"), preferred = listOf("1080p", "2160p"))),
        sort = StreamSort(global = listOf(SortCriterion(SortKey.RESOLUTION))),
    )

    @Test
    fun `default settings leave the list untouched`() {
        assertSame(streams, StreamPipeline.process(streams, StreamProcessingSettings(), context, 0))
    }

    @Test
    fun `excluded resolutions disappear and preferred ones sort first`() {
        val out = StreamPipeline.process(streams, settings, context, 0)
        assertEquals(listOf("B · 1080p", "B · 4k", "A · 720p"), out.map { it.label })
    }

    @Test
    fun `auto-play and LAST USED follow the processed list, raw text kept`() {
        val out = StreamPipeline.process(streams, settings, context, 0)
        assertEquals("B · 1080p", topStream(out, preferredAddonUrl = null)?.label)
        assertEquals("A · 720p", topStream(out, preferredAddonUrl = "https://a/manifest.json")?.label)
        val saved = streams[1].let { LastUsedStream(it.addonUrl, it.url, it.label, it.description) }
        assertEquals(2, lastUsedIndex(out, saved))
        out.forEach { processed ->
            val original = streams.single { it.url == processed.url }
            assertEquals(original.label, processed.label)
            assertEquals(original.description, processed.description)
            assertNull(processed.display)
        }
    }

    @Test
    fun `a formatter sets display while LAST USED still matches the raw text`() {
        val formatted = settings.copy(formatter = StreamFormatterChoice(FormatterId.PRISM))
        val out = StreamPipeline.process(streams, formatted, context, 0)
        assertEquals("🚀 FHD", out.first().display?.name)
        val saved = streams[3].let { LastUsedStream(it.addonUrl, "https://signed/new-url", it.label, it.description) }
        assertEquals(1, lastUsedIndex(out, saved))
    }

    @Test
    fun `a broken custom template shows the mistake instead of failing`() {
        val custom = StreamProcessingSettings(formatter = StreamFormatterChoice(FormatterId.CUSTOM, "{stream.nope}{", "{stream.size::bogus}"))
        val out = StreamPipeline.process(streams.take(1), custom, context, 0).single()
        assertEquals("{invalid_expression(stream.nope)}{", out.display?.name)
        assertEquals("{invalid_expression(stream.size::bogus)}", out.display?.description)
        assertEquals(streams[0].label, out.label)
    }
}
