package com.seastreams.tv.domain.usecase

import com.seastreams.tv.core.database.entity.AddonEntity
import com.seastreams.tv.core.network.StremioAddonClient
import com.seastreams.tv.core.network.StremioStream
import com.seastreams.tv.data.addon.BuiltInAddons
import com.seastreams.tv.data.usenet.newznab.UsenetSearchResult
import com.seastreams.tv.data.usenet.newznab.UsenetStreamSearch
import com.seastreams.tv.domain.model.MediaRef
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.StreamSource
import com.seastreams.tv.domain.streams.ContentKind
import com.seastreams.tv.domain.streams.ProcessStreamsUseCase
import com.seastreams.tv.domain.streams.StreamContext
import com.seastreams.tv.testutil.FakeAddonDao
import com.seastreams.tv.testutil.noStreamProcessing
import com.seastreams.tv.usenet.session.UsenetStreamRegistry
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import com.seastreams.tv.testutil.usenetSettings

/** The built-in `Usenet` entry's place in the profile's addon order decides where its card and results go. */
class ResolveStreamsUsenetOrderTest {
    private val client = mockk<StremioAddonClient>()
    private val search = mockk<UsenetStreamSearch>()
    private val movie = StreamQuery(1, MediaRef(603, MediaType.MOVIE), "tt0133093", null, "The Matrix", 1999)
    private val usenetRelease = StreamSource(UsenetStreamSearch.USENET_ADDON_URL, "Usenet", "seastreams-nntp://x", "Usenet · 1080p", "The.Matrix.1999.1080p", emptyMap())

    private fun addon(host: String, sortOrder: Int) = AddonEntity(
        profileId = 1, manifestUrl = "https://$host/manifest.json", manifestId = "com.example.$host", name = host, customName = null,
        enabled = true, sortOrder = sortOrder,
        manifestJson = """{"id":"com.example.$host","version":"1","name":"$host","types":["movie"],"idPrefixes":["tt"],"resources":["stream"]}""",
        installedAtMillis = 0,
    )

    private fun stub() {
        coEvery { client.fetchStreams("https://a/manifest.json", any(), any()) } returns listOf(StremioStream("https://cdn/a.mkv", "A", "a", emptyMap()))
        coEvery { client.fetchStreams("https://b/manifest.json", any(), any()) } returns listOf(StremioStream("https://cdn/b.mkv", "B", "b", emptyMap()))
        coEvery { client.fetchSubtitles(any(), any(), any()) } returns emptyList()
        coEvery { search.configured() } returns true
        coEvery { search.search(any()) } returns UsenetSearchResult(listOf(usenetRelease), failed = false)
    }

    private fun useCase(rows: List<AddonEntity>, process: ProcessStreamsUseCase = noStreamProcessing()) =
        ResolveStreamsUseCase(FakeAddonDao(rows), Lazy { client }, Lazy { search }, UsenetStreamRegistry(), process, usenetSettings())

    @Test
    fun `Usenet moved between two addons sits between them, card and results`() = runTest {
        stub()
        val rows = listOf(addon("a", 0), BuiltInAddons.usenet(1, 1, 0).copy(customName = "My Usenet"), addon("b", 2))
        val result = useCase(rows)(movie)

        assertEquals(listOf("https://a/manifest.json", UsenetStreamSearch.USENET_ADDON_URL, "https://b/manifest.json"), result.streamAddons.map { it.url })
        assertEquals("My Usenet", result.streamAddons[1].name)
        assertEquals(listOf("https://cdn/a.mkv", "seastreams-nntp://x", "https://cdn/b.mkv"), result.streams.map { it.url })
        assertEquals(usenetRelease, topStream(result.streams, preferredAddonUrl = UsenetStreamSearch.USENET_ADDON_URL))
    }

    @Test
    fun `Usenet first, and no entry yet means the bottom`() = runTest {
        stub()
        val first = useCase(listOf(BuiltInAddons.usenet(1, 0, 0), addon("a", 1), addon("b", 2)))(movie)
        assertEquals(UsenetStreamSearch.USENET_ADDON_URL, first.streamAddons.first().url)
        assertEquals("seastreams-nntp://x", first.streams.first().url)

        val none = useCase(listOf(addon("a", 0), addon("b", 1)))(movie)
        assertEquals(UsenetStreamSearch.USENET_ADDON_URL, none.streamAddons.last().url)
    }

    @Test
    fun `a profile that switched Usenet off is not searched`() = runTest {
        stub()
        val result = useCase(listOf(addon("a", 0), BuiltInAddons.usenet(1, 1, 0).copy(enabled = false)))(movie)
        assertEquals(listOf("https://a/manifest.json"), result.streamAddons.map { it.url })
        coVerify(exactly = 0) { search.search(any()) }
    }

    @Test
    fun `the Addon sort criterion gets Usenet's position`() = runTest {
        stub()
        val context = slot<StreamContext>()
        val process = mockk<ProcessStreamsUseCase> { coEvery { this@mockk.invoke(any(), any(), capture(context)) } answers { secondArg() } }
        useCase(listOf(addon("a", 0), BuiltInAddons.usenet(1, 1, 0), addon("b", 2)), process)(movie)
        assertEquals(listOf("https://a/manifest.json", BuiltInAddons.USENET_URL, "https://b/manifest.json"), context.captured.addonOrder)
        assertEquals(ContentKind.MOVIE, context.captured.kind)
    }
}
