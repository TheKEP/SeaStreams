package com.seastreams.tv.domain.streams

import com.seastreams.tv.domain.streams.filter.ResultLimiter
import com.seastreams.tv.domain.streams.filter.StreamDeduplicator
import com.seastreams.tv.domain.streams.sort.StreamSorter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StreamSortDedupTest {

    private val a720 = candidate(stream("Show.2020.720p.WEB-DL.x264-A", addon = "a", sizeBytes = 2 * GB))
    private val a2160 = candidate(stream("Show.2020.2160p.WEB-DL.HDR.DV.HEVC-A", addon = "a", sizeBytes = 30 * GB))
    private val b1080 = candidate(stream("Show.2020.1080p.BluRay.x264-B", addon = "b", sizeBytes = 10 * GB))
    private val bNone = candidate(stream("Show.2020.WEB-B", addon = "b"))
    private val list = listOf(a720, a2160, b1080, bNone)

    private fun names(items: List<com.seastreams.tv.domain.streams.filter.StreamCandidate>) = items.map { it.parsed.release.resolution ?: "?" }

    private fun sorter(sort: List<SortCriterion>, filters: StreamFilters = StreamFilters(), kind: ContentKind = ContentKind.MOVIE) =
        names(StreamSorter(StreamSort(global = sort), filters, listOf("https://b/manifest.json", "https://a/manifest.json")).sort(list, kind))

    @Test
    fun `preferred resolutions sort first in preferred order, others after, stable`() {
        val filters = StreamFilters(resolutions = AttributeFilter(preferred = listOf("1080p", "2160p")))
        assertEquals(listOf("1080p", "2160p", "720p", "?"), sorter(listOf(SortCriterion(SortKey.RESOLUTION)), filters))
        assertEquals(listOf("720p", "?", "2160p", "1080p"), sorter(listOf(SortCriterion(SortKey.RESOLUTION, SortDirection.ASC)), filters))
    }

    @Test
    fun `numeric criteria, per-criterion direction and tie-breaks`() {
        assertEquals(listOf("2160p", "1080p", "720p", "?"), sorter(listOf(SortCriterion(SortKey.SIZE))))
        assertEquals(listOf("?", "720p", "1080p", "2160p"), sorter(listOf(SortCriterion(SortKey.SIZE, SortDirection.ASC))))
        // Addon order (b before a) first, then size within each addon.
        assertEquals(listOf("1080p", "?", "2160p", "720p"), sorter(listOf(SortCriterion(SortKey.ADDON), SortCriterion(SortKey.SIZE))))
    }

    @Test
    fun `multi-valued preferred tags rank by the best tag`() {
        val filters = StreamFilters(visualTags = AttributeFilter(preferred = listOf("HDR+DV", "HDR")))
        assertEquals("2160p", sorter(listOf(SortCriterion(SortKey.VISUAL_TAG)), filters).first())
    }

    @Test
    fun `type lists replace Global only when non-empty`() {
        val sort = StreamSort(global = listOf(SortCriterion(SortKey.SIZE)), series = listOf(SortCriterion(SortKey.SIZE, SortDirection.ASC)))
        assertEquals(listOf(SortCriterion(SortKey.SIZE)), sort.effective(ContentKind.MOVIE))
        assertEquals(listOf(SortCriterion(SortKey.SIZE, SortDirection.ASC)), sort.effective(ContentKind.SERIES))
        assertEquals(listOf(SortCriterion(SortKey.SIZE)), sort.effective(ContentKind.ANIME))
    }

    @Test
    fun `regex and keyword preferences`() {
        val filters = StreamFilters(
            regex = RegexRules(preferred = listOf(NamedRegex("/bluray/i", "Disc"), NamedRegex("/hevc/i", "HEVC"))),
            keywords = AttributeFilter(preferred = listOf("x264")),
        )
        val annotated = StreamPipeline.annotatePreferred(list, filters)
        assertEquals(listOf(null, "HEVC", "Disc", null), annotated.map { it.parsed.regexMatched })
        assertEquals(listOf(true, false, true, false), annotated.map { it.parsed.keywordMatched })
        val byRegex = StreamSorter(StreamSort(global = listOf(SortCriterion(SortKey.REGEX))), filters, emptyList()).sort(annotated, ContentKind.MOVIE)
        assertEquals(listOf("1080p", "2160p", "720p", "?"), names(byRegex))
        val byKeyword = StreamSorter(StreamSort(global = listOf(SortCriterion(SortKey.KEYWORD))), filters, emptyList()).sort(annotated, ContentKind.MOVIE)
        assertEquals(listOf("720p", "1080p", "2160p", "?"), names(byKeyword))
    }

    @Test
    fun `dedupe by file name per addon or across addons, by url, first wins`() {
        val one = candidate(stream("Movie.2020.1080p.WEB.x264-G.mkv", addon = "a", url = "https://x/1"))
        val sameNameOtherCase = candidate(stream("movie 2020 1080p web x264 g", addon = "a", url = "https://x/2"))
        val otherAddon = candidate(stream("Movie.2020.1080p.WEB.x264-G.mkv", addon = "b", url = "https://x/3"))
        val sameUrl = candidate(stream("Different.Name.2020.mkv", addon = "b", url = "https://x/3"))
        val items = listOf(one, sameNameOtherCase, otherAddon, sameUrl)
        val perAddon = StreamDeduplicator.deduplicate(items, DedupSettings(enabled = true))
        assertEquals(listOf("https://x/1", "https://x/3"), perAddon.map { it.source.url })
        val single = StreamDeduplicator.deduplicate(items, DedupSettings(enabled = true, mode = DedupMode.SINGLE_RESULT))
        assertEquals(listOf("https://x/1"), single.map { it.source.url })
        assertEquals(items, StreamDeduplicator.deduplicate(items, DedupSettings(enabled = false)))
    }

    @Test
    fun `smart detect groups releases with near-equal size and the same tags`() {
        val x = candidate(stream("Film.2020.1080p.BluRay.x264-A", addon = "a", sizeBytes = 10 * GB))
        val y = candidate(stream("Film.2020.1080p.BluRay.x264-B", addon = "b", sizeBytes = 10 * GB + 100_000_000))
        val z = candidate(stream("Film.2020.1080p.BluRay.x264-C", addon = "c", sizeBytes = 20 * GB))
        val out = StreamDeduplicator.deduplicate(listOf(x, y, z), DedupSettings(enabled = true, keys = listOf(DedupKey.SMART), mode = DedupMode.SINGLE_RESULT))
        assertEquals(listOf(x, z), out)
    }

    @Test
    fun `result limits count globally and per resolution, quality and addon`() {
        assertEquals(2, ResultLimiter.limit(list, ResultLimits(global = 2)).size)
        assertEquals(listOf(a720, b1080), ResultLimiter.limit(list, ResultLimits(perAddon = 1)))
        assertEquals(listOf(a720, b1080), ResultLimiter.limit(list, ResultLimits(perQuality = 1)))
        assertEquals(list, ResultLimiter.limit(list, ResultLimits(perResolution = 1)))
    }
}
