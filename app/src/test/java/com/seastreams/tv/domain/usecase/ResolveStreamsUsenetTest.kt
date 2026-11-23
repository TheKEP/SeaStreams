package com.seastreams.tv.domain.usecase

import com.seastreams.tv.testutil.noStreamProcessing
import com.seastreams.tv.core.database.entity.AddonEntity
import com.seastreams.tv.core.network.StremioAddonClient
import com.seastreams.tv.core.network.StremioResourceParser
import com.seastreams.tv.core.network.StremioStream
import com.seastreams.tv.data.usenet.newznab.NzbRequest
import com.seastreams.tv.data.usenet.newznab.UsenetSearchResult
import com.seastreams.tv.data.usenet.newznab.UsenetSearchTarget
import com.seastreams.tv.data.usenet.newznab.UsenetStreamSearch
import com.seastreams.tv.domain.model.EpisodeRef
import com.seastreams.tv.domain.model.MediaRef
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.StreamAddon
import com.seastreams.tv.domain.model.StreamSource
import com.seastreams.tv.domain.playback.LastUsedStream
import com.seastreams.tv.domain.playback.lastUsedIndex
import com.seastreams.tv.testutil.FakeAddonDao
import com.seastreams.tv.usenet.session.AddonServers
import com.seastreams.tv.usenet.session.UsenetGrab
import com.seastreams.tv.usenet.session.UsenetStreamRegistry
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import com.seastreams.tv.testutil.usenetSettings

/** PRD §12 Playback: the built-in `Usenet` card, addon `nzbUrl` streams, and LAST USED on Usenet releases. */
class ResolveStreamsUsenetTest {
    private val client = mockk<StremioAddonClient>()
    private val search = mockk<UsenetStreamSearch>()
    private val registry = UsenetStreamRegistry()
    private val movie = StreamQuery(1, MediaRef(603, MediaType.MOVIE), "tt0133093", null, "The Matrix", 1999)
    private val usenetCard = StreamAddon(UsenetStreamSearch.USENET_ADDON_URL, UsenetStreamSearch.USENET_ADDON_NAME)

    private fun addon(host: String, sortOrder: Int = 0) = AddonEntity(
        profileId = 1,
        manifestUrl = "https://$host/manifest.json",
        manifestId = "com.example.$host",
        name = host,
        customName = null,
        enabled = true,
        sortOrder = sortOrder,
        manifestJson = """{"id":"com.example.$host","version":"1","name":"$host","types":["movie","series"],"idPrefixes":["tt"],"resources":["stream"]}""",
        installedAtMillis = 0,
    )

    private fun usenetStream(title: String) = StreamSource(
        UsenetStreamSearch.USENET_ADDON_URL, "Usenet", registry.register(
            UsenetGrab.Indexed(1, "Idx", title, NzbRequest("https://idx/get?id=$title&apikey=SECRET", "k$title"), title, 1),
        ), "Usenet · 1080p", "$title\n4.0 GB · Idx", emptyMap(),
    )

    private fun useCase(usenetOn: Boolean = true) =
        ResolveStreamsUseCase(FakeAddonDao(listOf(addon("torrentio"))), Lazy { client }, Lazy { search }, registry, noStreamProcessing(), usenetSettings(usenetOn))

    private fun stubAddon(streams: List<StremioStream> = listOf(StremioStream("https://cdn/a.mkv", "Torrentio", "A", emptyMap()))) {
        coEvery { client.fetchStreams(any(), any(), any()) } returns streams
        coEvery { client.fetchSubtitles(any(), any(), any()) } returns emptyList()
    }

    @Test
    fun `the Usenet card follows the Stremio addons when a provider and an indexer are enabled`() = runTest {
        stubAddon()
        val release = usenetStream("The.Matrix.1999.1080p")
        coEvery { search.configured() } returns true
        coEvery { search.search(any()) } returns UsenetSearchResult(listOf(release), failed = false)

        val result = useCase()(movie)

        assertEquals(listOf("https://torrentio/manifest.json", UsenetStreamSearch.USENET_ADDON_URL), result.streamAddons.map { it.url })
        assertEquals(listOf("https://cdn/a.mkv", release.url), result.streams.map { it.url })
        coVerify { search.search(UsenetSearchTarget.Movie("tt0133093", "The Matrix", 1999)) }
    }

    @Test
    fun `no card and no search without sources`() = runTest {
        stubAddon()
        coEvery { search.configured() } returns false
        val result = useCase()(movie)
        assertFalse(usenetCard in result.streamAddons)
        coVerify(exactly = 0) { search.search(any()) }
    }

    @Test
    fun `a failed Usenet search keeps its card and is reported`() = runTest {
        stubAddon()
        coEvery { search.configured() } returns true
        coEvery { search.search(any()) } returns UsenetSearchResult(emptyList(), failed = true)
        val result = useCase()(StreamQuery(1, MediaRef(1396, MediaType.TV), "tt0903747", EpisodeRef(5, 14), "Breaking Bad"))
        assertTrue(usenetCard in result.streamAddons)
        assertEquals(listOf("Usenet"), result.failedAddons)
        coVerify { search.search(UsenetSearchTarget.Episode("tt0903747", "Breaking Bad", 5, 14)) }
    }

    @Test
    fun `addon nzbUrl streams become opaque Usenet streams under their own addon`() = runTest {
        val raw = """{"streams":[
            {"name":"NZB\n1080p","description":"Matrix.1999.1080p","nzbUrl":"https://addon/nzb/1?key=SECRET",
             "servers":["nntps://u%40x:p%3Ass@news.example.com:563/8","ftp://nope"]},
            {"name":"Direct","url":"https://cdn/b.mkv"},
            {"name":"Torrent","infoHash":"abc"}]}"""
        val parsed = StremioResourceParser.parseStreams(raw)
        assertEquals(2, parsed.size)
        assertEquals("https://addon/nzb/1?key=SECRET", parsed[0].nzbUrl)
        stubAddon(parsed)
        coEvery { search.configured() } returns false

        val streams = useCase()(movie).streams

        assertTrue(UsenetStreamRegistry.isUsenet(streams[0].url))
        assertFalse(streams[0].url.contains("SECRET"))
        assertEquals("https://torrentio/manifest.json", streams[0].addonUrl)
        val grab = registry.get(checkNotNull(UsenetStreamRegistry.tokenOf(streams[0].url))) as UsenetGrab.FromAddon
        val server = grab.servers.single()
        assertEquals("news.example.com", server.host)
        assertEquals("u@x", server.username)
        assertEquals("p:ss", server.password)
        assertEquals(8, server.maxConnections)
        assertTrue(server.tls)
        assertEquals("https://cdn/b.mkv", streams[1].url)
    }

    @Test
    fun `with Enable Usenet OFF, nzbUrl streams needing your providers are dropped and own-servers ones still listed`() = runTest {
        val raw = """{"streams":[
            {"name":"Own servers","description":"A","nzbUrl":"https://addon/nzb/own","servers":["nntps://news.example.com:563/8"]},
            {"name":"Your providers","description":"B","nzbUrl":"https://addon/nzb/yours"},
            {"name":"Bad servers only","description":"C","nzbUrl":"https://addon/nzb/bad","servers":["ftp://nope"]},
            {"name":"Direct","url":"https://cdn/b.mkv"}]}"""
        stubAddon(StremioResourceParser.parseStreams(raw))
        coEvery { search.configured() } returns false

        val off = useCase(usenetOn = false)(movie).streams
        assertEquals(listOf("Own servers", "Direct"), off.map { it.label })
        assertTrue(UsenetStreamRegistry.isUsenet(off[0].url))

        val on = useCase(usenetOn = true)(movie).streams
        assertEquals(listOf("Own servers", "Your providers", "Bad servers only", "Direct"), on.map { it.label })
    }

    @Test
    fun `addon servers skip malformed and line-break entries`() {
        val servers = AddonServers.parse(listOf("nntp://h.example", "nntps://a:b%0D%0AQUIT@h2.example/3", "http://h3", "nntps://h4.example:443/500"))
        assertEquals(listOf("h.example", "h4.example"), servers.map { it.host })
        assertEquals(119, servers[0].port)
        assertEquals(4, servers[0].maxConnections)
        assertEquals(443, servers[1].port)
        assertEquals(50, servers[1].maxConnections)
    }

    @Test
    fun `LAST USED finds a Usenet release again by its stable URL in a new search`() {
        val first = usenetStream("Matrix.1999.2160p")
        val saved = LastUsedStream(first.addonUrl, first.url, first.label, first.description)
        // A later search registers the same release again (fresh registry entry, same identity).
        val again = listOf(usenetStream("Matrix.1999.720p"), usenetStream("Matrix.1999.2160p"))
        assertEquals(first.url, again[1].url)
        assertEquals(1, lastUsedIndex(again, saved))
        assertFalse(first.url.contains("SECRET"))
    }
}
