package com.seastreams.tv.domain.usecase

import com.seastreams.tv.core.database.entity.AddonEntity
import com.seastreams.tv.core.network.StremioAddonClient
import com.seastreams.tv.core.network.StremioStream
import com.seastreams.tv.data.addon.BuiltInAddons
import com.seastreams.tv.data.debrid.library.DebridLibrarySearch
import com.seastreams.tv.data.debrid.library.DebridSearchResult
import com.seastreams.tv.data.usenet.newznab.UsenetSearchResult
import com.seastreams.tv.data.usenet.newznab.UsenetSearchTarget
import com.seastreams.tv.data.usenet.newznab.UsenetStreamSearch
import com.seastreams.tv.domain.model.EpisodeRef
import com.seastreams.tv.domain.model.MediaRef
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.StreamHints
import com.seastreams.tv.domain.model.StreamSource
import com.seastreams.tv.domain.streams.ProcessStreamsUseCase
import com.seastreams.tv.domain.streams.StreamContext
import com.seastreams.tv.testutil.FakeAddonDao
import com.seastreams.tv.testutil.noStreamProcessing
import com.seastreams.tv.testutil.usenetSettings
import com.seastreams.tv.usenet.session.UsenetStreamRegistry
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** PRD §14 *Addon list & streams*: the `Debrid` card and its results sit at the profile's `Debrid` entry. */
class ResolveStreamsDebridTest {
    private val client = mockk<StremioAddonClient>()
    private val usenet = mockk<UsenetStreamSearch>()
    private val debrid = mockk<DebridLibrarySearch>()
    private val movie = StreamQuery(1, MediaRef(27205, MediaType.MOVIE), "tt1375666", null, "Inception", 2010)
    private val debridFile = StreamSource(
        DebridLibrarySearch.DEBRID_ADDON_URL, "Debrid", "seastreams-debrid://" + "a".repeat(32), "Real-Debrid · 1080p",
        "Inception.2010.1080p.mkv\n8.0 GB · Real-Debrid", emptyMap(), StreamHints("Inception.2010.1080p.mkv", 8_000_000_000, indexer = "Real-Debrid"),
    )
    private val usenetRelease = StreamSource(UsenetStreamSearch.USENET_ADDON_URL, "Usenet", "seastreams-nntp://x", "Usenet · 1080p", "Inception.2010.1080p", emptyMap())

    private fun addon(host: String, sortOrder: Int, profileId: Long = 1) = AddonEntity(
        profileId = profileId, manifestUrl = "https://$host/manifest.json", manifestId = "com.example.$host", name = host, customName = null,
        enabled = true, sortOrder = sortOrder,
        manifestJson = """{"id":"com.example.$host","version":"1","name":"$host","types":["movie","series"],"idPrefixes":["tt"],"resources":["stream"]}""",
        installedAtMillis = 0,
    )

    private fun stub(debridConfigured: Boolean = true, usenetConfigured: Boolean = false) {
        coEvery { client.fetchStreams(any(), any(), any()) } answers { listOf(StremioStream("https://cdn/${firstArg<String>().length}.mkv", "A", "a", emptyMap())) }
        coEvery { client.fetchSubtitles(any(), any(), any()) } returns emptyList()
        coEvery { usenet.configured() } returns usenetConfigured
        coEvery { usenet.search(any()) } returns UsenetSearchResult(listOf(usenetRelease), failed = false)
        coEvery { debrid.configured() } returns debridConfigured
        coEvery { debrid.search(any()) } returns DebridSearchResult(listOf(debridFile), failed = false)
    }

    private fun useCase(rows: List<AddonEntity>, process: ProcessStreamsUseCase = noStreamProcessing()) =
        ResolveStreamsUseCase(FakeAddonDao(rows), Lazy { client }, Lazy { usenet }, UsenetStreamRegistry(), process, usenetSettings(), Lazy { debrid })

    @Test
    fun `no card and no search call while Debrid isn't configured`() = runTest {
        stub(debridConfigured = false)
        val result = useCase(listOf(addon("a", 0), BuiltInAddons.debrid(1, 1, 0)))(movie)

        assertEquals(listOf("https://a/manifest.json"), result.streamAddons.map { it.url })
        coVerify(exactly = 0) { debrid.search(any()) }
    }

    @Test
    fun `the Debrid card and results sit at the entry, under its custom name, beside Usenet's`() = runTest {
        stub(usenetConfigured = true)
        val rows = listOf(
            addon("a", 0), BuiltInAddons.debrid(1, 1, 0).copy(customName = "My cloud"), addon("b", 2), BuiltInAddons.usenet(1, 3, 0),
        )
        val result = useCase(rows)(movie)

        assertEquals(
            listOf("https://a/manifest.json", BuiltInAddons.DEBRID_URL, "https://b/manifest.json", BuiltInAddons.USENET_URL),
            result.streamAddons.map { it.url },
        )
        assertEquals("My cloud", result.streamAddons[1].name)
        assertEquals(listOf("https://a/manifest.json", BuiltInAddons.DEBRID_URL, "https://b/manifest.json", BuiltInAddons.USENET_URL), result.streams.map { it.addonUrl })
    }

    @Test
    fun `a profile without an entry yet gets Debrid at the bottom, after Usenet`() = runTest {
        stub(usenetConfigured = true)
        val result = useCase(listOf(addon("a", 0)))(movie)

        assertEquals(listOf("https://a/manifest.json", BuiltInAddons.USENET_URL, BuiltInAddons.DEBRID_URL), result.streamAddons.map { it.url })
    }

    @Test
    fun `one profile switching Debrid off never affects another`() = runTest {
        stub()
        val rows = listOf(
            addon("a", 0, profileId = 1), BuiltInAddons.debrid(1, 1, 0).copy(enabled = false),
            addon("a", 0, profileId = 2), BuiltInAddons.debrid(2, 1, 0),
        )
        val useCase = useCase(rows)

        val off = useCase(movie)
        assertEquals(listOf("https://a/manifest.json"), off.streamAddons.map { it.url })
        coVerify(exactly = 0) { debrid.search(any()) }

        val on = useCase(movie.copy(profileId = 2))
        assertEquals(listOf("https://a/manifest.json", BuiltInAddons.DEBRID_URL), on.streamAddons.map { it.url })
        assertTrue(on.streams.any { it.url == debridFile.url })
    }

    @Test
    fun `the Addon sort criterion gets Debrid's position and episodes search their own target`() = runTest {
        stub()
        val context = slot<StreamContext>()
        val process = mockk<ProcessStreamsUseCase> { coEvery { this@mockk.invoke(any(), any(), capture(context)) } answers { secondArg() } }
        val target = slot<UsenetSearchTarget>()
        coEvery { debrid.search(capture(target)) } returns DebridSearchResult(listOf(debridFile), failed = false)

        val episode = StreamQuery(1, MediaRef(1396, MediaType.TV), "tt0903747", EpisodeRef(2, 5), "Breaking Bad")
        useCase(listOf(BuiltInAddons.debrid(1, 0, 0), addon("a", 1)), process)(episode)

        assertEquals(listOf(BuiltInAddons.DEBRID_URL, "https://a/manifest.json"), context.captured.addonOrder)
        assertEquals(UsenetSearchTarget.Episode("tt0903747", "Breaking Bad", 2, 5), target.captured)
    }

    @Test
    fun `Preferred auto-play addon can pick the Debrid entry`() = runTest {
        stub()
        val result = useCase(listOf(addon("a", 0), BuiltInAddons.debrid(1, 1, 0)))(movie)

        assertEquals("https://a/manifest.json", topStream(result.streams, null)?.addonUrl)
        assertEquals(debridFile.url, topStream(result.streams, BuiltInAddons.DEBRID_URL)?.url)
    }

    @Test
    fun `a failed Debrid search keeps its card and is reported`() = runTest {
        stub()
        coEvery { debrid.search(any()) } returns DebridSearchResult(emptyList(), failed = true)
        val result = useCase(listOf(addon("a", 0), BuiltInAddons.debrid(1, 1, 0)))(movie)

        assertEquals(BuiltInAddons.DEBRID_URL, result.streamAddons.last().url)
        assertEquals(listOf("Debrid"), result.failedAddons)
    }
}
