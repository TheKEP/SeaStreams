package com.seastreams.tv.domain.streams

import com.seastreams.tv.domain.model.StreamHints
import com.seastreams.tv.domain.model.StreamSource
import com.seastreams.tv.domain.streams.parse.LanguageTable
import com.seastreams.tv.domain.streams.parse.ReleaseInfo
import com.seastreams.tv.domain.streams.parse.ReleaseParser
import com.seastreams.tv.domain.streams.parse.StreamFacts
import com.seastreams.tv.domain.streams.parse.StreamKind
import com.seastreams.tv.domain.streams.parse.withFakeVisualTags
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.roundToLong

class ReleaseParserTest {

    @Test
    fun `the formatter preview release`() {
        val r = ReleaseParser.parse("Movie.Title.2023.2160p.BluRay.HEVC.DV.TrueHD.Atmos.7.1.iTA.ENG-GROUP.mkv")
        assertEquals(
            ReleaseInfo(
                title = "Movie Title", year = "2023", resolution = "2160p", quality = "BluRay", encode = "HEVC",
                visualTags = listOf("DV"), audioTags = listOf("Atmos", "TrueHD"), audioChannels = listOf("7.1"),
                languages = listOf("English", "Italian"), releaseGroup = "GROUP", extension = "mkv",
            ),
            r,
        )
    }

    @Test
    fun `scene web release with network and season episode`() {
        val r = ReleaseParser.parse("The.Last.of.Us.S01E03.Long.Long.Time.1080p.AMZN.WEB-DL.DDP5.1.H.264-NTb")
        assertEquals("The Last of Us", r.title)
        assertEquals(listOf(1), r.seasons)
        assertEquals(listOf(3), r.episodes)
        assertEquals("1080p", r.resolution)
        assertEquals("WEB-DL", r.quality)
        assertEquals("AVC", r.encode)
        assertEquals(listOf("DD+"), r.audioTags)
        assertEquals(listOf("5.1"), r.audioChannels)
        assertEquals("Amazon", r.network)
        assertEquals("NTb", r.releaseGroup)
    }

    @Test
    fun `remux with HDR10+ and DTS-HD MA, year in parentheses`() {
        val r = ReleaseParser.parse("Dune Part Two (2024) 2160p UHD BluRay REMUX HDR10+ DTS-HD MA 7.1 HEVC-FraMeSToR")
        assertEquals("Dune Part Two", r.title)
        assertEquals("2024", r.year)
        assertEquals("2160p", r.resolution)
        assertEquals("BluRay REMUX", r.quality)
        assertEquals(listOf("HDR10+"), r.visualTags)
        assertEquals(listOf("DTS-HD MA"), r.audioTags)
        assertEquals("FraMeSToR", r.releaseGroup)
    }

    @Test
    fun `season pack, multi-episode, anime and editions`() {
        val pack = ReleaseParser.parse("Breaking.Bad.S01-S05.COMPLETE.720p.BluRay.x264-DEMAND")
        assertEquals(listOf(1, 2, 3, 4, 5), pack.seasons)
        assertTrue(pack.seasonPack)
        assertEquals(listOf(4, 5), ReleaseParser.parse("Show.S02E04E05.720p.HDTV.x264-GRP").episodes)
        val anime = ReleaseParser.parse("[SubsPlease] Frieren - 12 (1080p) [ABCD1234].mkv")
        assertEquals("Frieren", anime.title)
        assertEquals(listOf(12), anime.episodes)
        assertEquals("SubsPlease", anime.releaseGroup)
        val cut = ReleaseParser.parse("Aliens.1986.Directors.Cut.REMASTERED.1080p.BluRay.x265-RARBG")
        assertEquals(listOf("Director's Cut", "Remastered"), cut.editions)
    }

    @Test
    fun `the title is cut out before tags are read`() {
        // "Hd" and "Ita" inside the title must not read as tags.
        val r = ReleaseParser.parse("Ita.Hd.Story.2020.1080p.WEB.x264-GRP")
        assertEquals("Ita Hd Story", r.title)
        assertEquals(emptyList<String>(), r.languages)
        assertEquals("1080p", r.resolution)
    }

    @Test
    fun `stream facts from Torrentio-style text`() {
        val stream = StreamSource(
            addonUrl = "https://torrentio", addonName = "Torrentio", url = "https://x/y.mkv",
            label = "Torrentio · [RD+] 4k",
            description = "Movie.2021.2160p.WEB-DL.DV.mkv\n👤 42 💾 14.2 GB ⚙️ ThePirateBay\n🇬🇧 / 🇫🇷",
            headers = emptyMap(),
        )
        val parsed = StreamFacts.parse(stream, durationMs = 7_200_000L, nowMillis = 0, usenetAddonUrl = "usenet://builtin")
        assertEquals("Movie.2021.2160p.WEB-DL.DV.mkv", parsed.filename)
        assertEquals(42, parsed.seeders)
        assertEquals((14.2 * 1024 * 1024 * 1024).roundToLong(), parsed.sizeBytes)
        assertEquals("ThePirateBay", parsed.indexer)
        assertEquals(StreamKind.DEBRID, parsed.type)
        assertEquals(listOf("English", "French"), parsed.languages)
        assertEquals(parsed.sizeBytes?.let { (it * 8 * 1000 / 7_200_000.0).roundToLong() }, parsed.bitrate)
    }

    @Test
    fun `Usenet hints give size, age and indexer`() {
        val stream = StreamSource(
            addonUrl = "usenet://builtin", addonName = "Usenet", url = "seastreams-nntp://abc",
            label = "Usenet · 1080p", description = "Film.2019.1080p.BluRay.x264-GRP\n8.0 GB · 3 days old · NZBgeek",
            headers = emptyMap(),
            hints = StreamHints("Film.2019.1080p.BluRay.x264-GRP", 8_000_000_000L, publishedMillis = 0, indexer = "NZBgeek"),
        )
        val parsed = StreamFacts.parse(stream, null, nowMillis = 72 * 3_600_000L, usenetAddonUrl = "usenet://builtin")
        assertEquals(StreamKind.USENET, parsed.type)
        assertEquals(72.0, parsed.ageHours)
        assertEquals(8_000_000_000L, parsed.sizeBytes)
        assertEquals("NZBgeek", parsed.indexer)
        assertNull(parsed.bitrate)
    }

    @Test
    fun `filename heuristic skips the label line and strips emoji`() {
        assertEquals(
            "Show.S01E02.720p.HDTV.x264-GRP",
            StreamFacts.filenameFrom("Best source\n📄 Show.S01E02.720p.HDTV.x264-GRP\n💾 1 GB"),
        )
        assertEquals("Some file name", StreamFacts.filenameFrom("Name: Some file name"))
    }

    @Test
    fun `language table and fake visual tags`() {
        assertEquals("English", LanguageTable.fromFlag("🇺🇸"))
        assertEquals("German", LanguageTable.fromCode("ger"))
        assertEquals("Portuguese (Brazil)", LanguageTable.fromCode("pt-BR"))
        assertEquals(listOf("DV", "HDR10", "HDR+DV"), withFakeVisualTags(listOf("DV", "HDR10")))
        assertEquals(listOf("DV", "DV Only"), withFakeVisualTags(listOf("DV")))
    }
}
