package com.seastreams.tv.domain.streams

import com.seastreams.tv.domain.streams.filter.KeywordMatcher
import com.seastreams.tv.domain.streams.filter.StreamFilterEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StreamFilterEngineTest {

    private val uhd = candidate(stream("Film.2020.2160p.WEB-DL.DV.HEVC-GRP", sizeBytes = 20 * GB), runtimeMinutes = 120)
    private val fhd = candidate(stream("Film.2020.1080p.BluRay.x264-OTHER", sizeBytes = 8 * GB), runtimeMinutes = 120)
    private val sd = candidate(stream("Film.2020.480p.DVDRip.XviD-OLD", sizeBytes = GB), runtimeMinutes = 120)
    private val unknown = candidate(stream("Film.2020.WEBRip-NONE"))
    private val all = listOf(uhd, fhd, sd, unknown)

    private fun keep(filters: StreamFilters, kind: ContentKind = ContentKind.MOVIE) =
        StreamFilterEngine(filters, kind).filter(all).map { it.parsed.release.resolution }

    @Test
    fun `excluded and required resolutions, with Unknown as a value`() {
        assertEquals(listOf("2160p", "1080p", null), keep(StreamFilters(resolutions = AttributeFilter(excluded = listOf("480p")))))
        assertEquals(listOf("2160p", "1080p", "480p"), keep(StreamFilters(resolutions = AttributeFilter(excluded = listOf("Unknown")))))
        assertEquals(listOf("1080p"), keep(StreamFilters(resolutions = AttributeFilter(required = listOf("1080p")))))
    }

    @Test
    fun `included overrides every other filter`() {
        val filters = StreamFilters(
            resolutions = AttributeFilter(required = listOf("2160p"), included = listOf("480p")),
            qualities = AttributeFilter(excluded = listOf("DVDRip")),
        )
        assertEquals(listOf("2160p", "480p"), keep(filters))
    }

    @Test
    fun `multi-valued tags are excluded when any matches and required when any matches`() {
        assertEquals(listOf("1080p", "480p", null), keep(StreamFilters(visualTags = AttributeFilter(excluded = listOf("DV")))))
        assertEquals(listOf("2160p"), keep(StreamFilters(visualTags = AttributeFilter(required = listOf("DV Only")))))
    }

    @Test
    fun `languages are excluded only when every language is excluded, required when any matches`() {
        val multi = candidate(stream("Film.2020.1080p.WEB.ITA.ENG-GRP"))
        val italian = candidate(stream("Film.2020.1080p.WEB.ITA-GRP"))
        val engine = StreamFilterEngine(StreamFilters(languages = AttributeFilter(excluded = listOf("Italian"))), ContentKind.MOVIE)
        assertTrue(engine.keep(multi.parsed))
        assertFalse(engine.keep(italian.parsed))
        val required = StreamFilterEngine(StreamFilters(languages = AttributeFilter(required = listOf("English", "French"))), ContentKind.MOVIE)
        assertTrue(required.keep(multi.parsed))
        assertFalse(required.keep(italian.parsed))
    }

    @Test
    fun `keywords match whole tokens of the file name, group or indexer`() {
        assertEquals(listOf("1080p", "480p", null), keep(StreamFilters(keywords = AttributeFilter(excluded = listOf("web dl")))))
        assertEquals(listOf("480p"), keep(StreamFilters(keywords = AttributeFilter(required = listOf("old")))))
        // "GR" is not a whole token of "GRP".
        assertEquals(4, keep(StreamFilters(keywords = AttributeFilter(excluded = listOf("GR")))).size)
    }

    @Test
    fun `regex filters, flags and the n negate flag`() {
        assertEquals(listOf("2160p", "1080p", null), keep(StreamFilters(regex = RegexRules(excluded = listOf("/xvid/i")))))
        assertEquals(listOf("480p"), keep(StreamFilters(regex = RegexRules(required = listOf("/XviD/")))))
        // Negated: excludes every stream that does NOT mention BluRay.
        assertEquals(listOf("1080p"), keep(StreamFilters(regex = RegexRules(excluded = listOf("/bluray/in")))))
        assertNull(KeywordMatcher.compile("/([unclosed/"))
        assertEquals(4, keep(StreamFilters(regex = RegexRules(excluded = listOf("/([unclosed/")))).size)
    }

    @Test
    fun `seeders and age only judge streams that state them`() {
        val seeded = candidate(stream("Film.2020.1080p.WEB-GRP", extra = "👤 3"))
        val popular = candidate(stream("Film.2020.1080p.WEB-GRP2", extra = "👤 300"))
        val engine = StreamFilterEngine(StreamFilters(seeders = RangeRule(required = ValueRange(min = 10))), ContentKind.MOVIE)
        assertFalse(engine.keep(seeded.parsed))
        assertTrue(engine.keep(popular.parsed))
        assertTrue(engine.keep(fhd.parsed))
        val excludedOld = StreamFilterEngine(StreamFilters(ageHours = RangeRule(excluded = ValueRange(min = 24 * 30))), ContentKind.MOVIE)
        assertFalse(excludedOld.keep(candidate(stream("A.2020.1080p-G", extra = "📅 40d")).parsed))
        assertTrue(excludedOld.keep(candidate(stream("A.2020.1080p-G", extra = "📅 2d")).parsed))
    }

    @Test
    fun `size per resolution for movies and series, global fallback`() {
        val size = SizeLimits(
            global = MovieSeriesRange(movies = ValueRange(max = 10 * GB)),
            byResolution = mapOf("2160p" to MovieSeriesRange(movies = ValueRange(max = 30 * GB), series = ValueRange(max = 5 * GB))),
        )
        assertEquals(listOf("2160p", "1080p", "480p", null), keep(StreamFilters(size = size), ContentKind.MOVIE))
        assertEquals(listOf("1080p", "480p", null), keep(StreamFilters(size = size), ContentKind.SERIES))
        val minimum = SizeLimits(global = MovieSeriesRange(movies = ValueRange(min = 5 * GB)))
        assertEquals(listOf("2160p", "1080p", null), keep(StreamFilters(size = minimum)))
    }

    @Test
    fun `bitrate is size over runtime`() {
        // 20 GB over 120 min ≈ 22 Mbps; 8 GB ≈ 8.9 Mbps; 1 GB ≈ 1.1 Mbps.
        val bitrate = SizeLimits(global = MovieSeriesRange(movies = ValueRange(min = 5_000_000, max = 15_000_000)))
        assertEquals(listOf("1080p", null), keep(StreamFilters(bitrate = bitrate)))
    }

    @Test
    fun `stream type, release group and encode lists`() {
        assertEquals(listOf("1080p"), keep(StreamFilters(releaseGroups = AttributeFilter(required = listOf("other")))))
        assertEquals(listOf("2160p"), keep(StreamFilters(encodes = AttributeFilter(required = listOf("HEVC")))))
        assertEquals(0, keep(StreamFilters(streamTypes = AttributeFilter(excluded = listOf("HTTP")))).size)
    }
}
