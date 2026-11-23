package com.seastreams.tv.domain.streams

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** PRD §13 *Master toggles*: each switch bypasses exactly one stage and keeps its stored rules. */
class StreamProcessingTogglesTest {

    private val streams = listOf(
        stream("Film.2020.480p.DVDRip.XviD-A", addon = "a", label = "480p", sizeBytes = 1 * GB),
        stream("Film.2020.720p.WEB-DL.x264-A", addon = "a", label = "720p", sizeBytes = 2 * GB),
        stream("Film.2020.1080p.BluRay.x264-B", addon = "b", label = "1080p", sizeBytes = 3 * GB),
        stream("Film.2020.2160p.WEB-DL.HEVC-B", addon = "b", label = "2160p", sizeBytes = 4 * GB),
    )
    private val context = StreamContext(ContentKind.MOVIE, addonOrder = listOf("https://a/manifest.json", "https://b/manifest.json"))

    /** Excludes 480p, prefers 1080p, sorts by preferred resolution then size, formats with Prism. */
    private val rules = StreamProcessingSettings(
        filters = StreamFilters(resolutions = AttributeFilter(excluded = listOf("480p"), preferred = listOf("1080p"))),
        sort = StreamSort(global = listOf(SortCriterion(SortKey.RESOLUTION), SortCriterion(SortKey.SIZE))),
        formatter = StreamFormatterChoice(FormatterId.PRISM),
    )

    private fun run(settings: StreamProcessingSettings) = StreamPipeline.process(streams, settings, context, 0)

    @Test
    fun `every stage on applies filters, sorting and formatter`() {
        val out = run(rules)
        assertEquals(listOf("1080p", "2160p", "720p"), out.map { it.label })
        assertTrue(out.all { it.display != null })
    }

    @Test
    fun `filtering off brings filtered streams back and ignores the Preferred lists`() {
        val out = run(rules.copy(filteringEnabled = false))
        // No exclusion, and preferred 1080p no longer ranks first: only the size criterion orders them.
        assertEquals(listOf("2160p", "1080p", "720p", "480p"), out.map { it.label })
        assertTrue(out.all { it.display != null })
    }

    @Test
    fun `filtering off also bypasses the deduplicator and result limits`() {
        val limited = rules.copy(
            filters = rules.filters.copy(limits = ResultLimits(global = 1), dedup = DedupSettings(enabled = true, keys = listOf(DedupKey.SMART))),
        )
        assertEquals(1, run(limited).size)
        assertEquals(4, run(limited.copy(filteringEnabled = false)).size)
    }

    @Test
    fun `sorting off keeps the addon order while filters and formatter still apply`() {
        val out = run(rules.copy(sortingEnabled = false))
        assertEquals(listOf("720p", "1080p", "2160p"), out.map { it.label })
        assertTrue(out.all { it.display != null })
    }

    @Test
    fun `formatting off leaves display empty so cards show the addon text`() {
        val out = run(rules.copy(formattingEnabled = false))
        assertEquals(listOf("1080p", "2160p", "720p"), out.map { it.label })
        out.forEach {
            assertNull(it.display)
            assertNotNull(it.parsed)
        }
    }

    @Test
    fun `every stage off returns the list untouched without parsing`() {
        val off = rules.copy(filteringEnabled = false, sortingEnabled = false, formattingEnabled = false)
        assertSame(streams, run(off))
        assertTrue(off.effective().isDefault)
        // The stored rules themselves are untouched by the bypass.
        assertEquals(rules.filters, off.filters)
        assertEquals(rules.sort, off.sort)
        assertEquals(rules.formatter, off.formatter)
    }

    @Test
    fun `stages left on with nothing to do count as no-ops`() {
        val onlyFilters = StreamProcessingSettings(filters = rules.filters, filteringEnabled = false)
        assertSame(streams, run(onlyFilters))
        val onlyFormatter = StreamProcessingSettings(formatter = rules.formatter, formattingEnabled = false)
        assertSame(streams, run(onlyFormatter))
    }

    @Test
    fun `turning a stage back on restores the original result`() {
        val before = run(rules)
        listOf(
            rules.copy(filteringEnabled = false),
            rules.copy(sortingEnabled = false),
            rules.copy(formattingEnabled = false),
        ).forEach { bypassed ->
            val restored = bypassed.copy(filteringEnabled = true, sortingEnabled = true, formattingEnabled = true)
            assertEquals(before, run(restored))
        }
    }
}
