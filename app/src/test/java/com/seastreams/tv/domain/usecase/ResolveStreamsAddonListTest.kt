package com.seastreams.tv.domain.usecase

import com.seastreams.tv.testutil.noStreamProcessing
import com.seastreams.tv.testutil.unconfiguredUsenet
import com.seastreams.tv.usenet.session.UsenetStreamRegistry
import com.seastreams.tv.core.database.entity.AddonEntity
import com.seastreams.tv.core.network.StremioAddonClient
import com.seastreams.tv.core.network.StremioStream
import com.seastreams.tv.domain.model.MediaRef
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.StreamAddon
import com.seastreams.tv.testutil.FakeAddonDao
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.IOException
import com.seastreams.tv.testutil.usenetSettings

/** PRD §6 Addon Selector Cards: which addons get a card, and in what order. */
class ResolveStreamsAddonListTest {

    private val client = mockk<StremioAddonClient>()
    private val movie = StreamQuery(1, MediaRef(603, MediaType.MOVIE), imdbId = "tt0133093", episode = null)

    private fun addon(
        host: String,
        sortOrder: Int,
        resources: String = """["stream"]""",
        types: String = """["movie","series"]""",
        idPrefixes: String = """["tt"]""",
        enabled: Boolean = true,
        customName: String? = null,
        url: String = "https://$host/manifest.json",
    ) = AddonEntity(
        profileId = 1,
        manifestUrl = url,
        manifestId = "com.example.$host",
        name = host,
        customName = customName,
        enabled = enabled,
        sortOrder = sortOrder,
        manifestJson = """{"id":"com.example.$host","version":"1","name":"$host","types":$types,""" +
            """"idPrefixes":$idPrefixes,"resources":$resources}""",
        installedAtMillis = 0,
    )

    @Test
    fun `every queryable stream addon gets a card in sortOrder, whatever it returned`() = runTest {
        val dao = FakeAddonDao(
            listOf(
                addon("third", sortOrder = 3),
                addon("broken", sortOrder = 2),
                addon("first", sortOrder = 0, customName = "My First"),
                addon("silent", sortOrder = 1),
                addon("subs", sortOrder = 4, resources = """["subtitles"]"""),
                addon("meta", sortOrder = 5, resources = """["catalog","meta"]"""),
                addon("shows", sortOrder = 6, types = """["series"]"""),
                addon("anime", sortOrder = 7, idPrefixes = """["kitsu:"]"""),
                addon("off", sortOrder = 8, enabled = false),
                addon("tmdb", sortOrder = 9, url = "builtin://tmdb"),
            ),
        )
        coEvery { client.fetchStreams(any(), any(), any()) } answers {
            when (val url = firstArg<String>()) {
                "https://silent/manifest.json" -> emptyList()
                "https://broken/manifest.json" -> throw IOException("down")
                else -> listOf(StremioStream("$url/s.mkv", "S", "", emptyMap()))
            }
        }
        coEvery { client.fetchSubtitles(any(), any(), any()) } returns emptyList()

        val result = ResolveStreamsUseCase(dao, Lazy { client }, unconfiguredUsenet(), UsenetStreamRegistry(), noStreamProcessing(), usenetSettings())(movie)

        assertEquals(
            listOf(
                StreamAddon("https://first/manifest.json", "My First"),
                StreamAddon("https://silent/manifest.json", "silent"),
                StreamAddon("https://broken/manifest.json", "broken"),
                StreamAddon("https://third/manifest.json", "third"),
            ),
            result.streamAddons,
        )
        assertEquals(listOf("broken"), result.failedAddons)
        // The flat list auto-play and fallback use is unchanged: addon order, only addons with streams.
        assertEquals(listOf("My First", "third"), result.streams.map { it.addonName })
    }

    @Test
    fun `the default preferred addon resolves to the first enabled stream addon in sortOrder`() = runTest {
        val dao = FakeAddonDao(
            listOf(
                addon("later", sortOrder = 3),
                addon("off", sortOrder = 0, enabled = false),
                addon("subs", sortOrder = 1, resources = """["subtitles"]"""),
                addon("first", sortOrder = 2),
            ),
        )
        coEvery { client.fetchStreams(any(), any(), any()) } answers {
            listOf(StremioStream("${firstArg<String>()}/s.mkv", "S", "", emptyMap()))
        }
        coEvery { client.fetchSubtitles(any(), any(), any()) } returns emptyList()

        val result = ResolveStreamsUseCase(dao, Lazy { client }, unconfiguredUsenet(), UsenetStreamRegistry(), noStreamProcessing(), usenetSettings())(movie)

        // PlayerSettings.preferredAutoPlayAddonUrl defaults to null = "First enabled addon".
        assertEquals("https://first/manifest.json", topStream(result.streams, null)?.addonUrl)
    }

    @Test
    fun `no enabled stream addons means no cards`() = runTest {
        val dao = FakeAddonDao(listOf(addon("subs", sortOrder = 0, resources = """["subtitles"]""")))
        coEvery { client.fetchSubtitles(any(), any(), any()) } returns emptyList()

        val result = ResolveStreamsUseCase(dao, Lazy { client }, unconfiguredUsenet(), UsenetStreamRegistry(), noStreamProcessing(), usenetSettings())(movie)

        assertEquals(emptyList<StreamAddon>(), result.streamAddons)
    }
}
