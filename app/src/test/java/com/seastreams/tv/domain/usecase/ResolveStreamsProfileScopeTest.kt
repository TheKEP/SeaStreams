package com.seastreams.tv.domain.usecase

import com.seastreams.tv.testutil.noStreamProcessing
import com.seastreams.tv.testutil.unconfiguredUsenet
import com.seastreams.tv.usenet.session.UsenetStreamRegistry
import com.seastreams.tv.core.database.entity.AddonEntity
import com.seastreams.tv.core.network.StremioAddonClient
import com.seastreams.tv.core.network.StremioStream
import com.seastreams.tv.core.network.StremioSubtitle
import com.seastreams.tv.domain.model.MediaRef
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.testutil.FakeAddonDao
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Streams and subtitles come only from the requesting profile's own enabled addons. */
class ResolveStreamsProfileScopeTest {

    private val client = mockk<StremioAddonClient>()

    private fun addon(profileId: Long, host: String, enabled: Boolean = true) = AddonEntity(
        profileId = profileId,
        manifestUrl = "https://$host/manifest.json",
        manifestId = "com.example.$host",
        name = host,
        customName = null,
        enabled = enabled,
        sortOrder = 0,
        manifestJson = """{"id":"com.example.$host","version":"1","name":"$host","types":["movie"],""" +
            """"idPrefixes":["tt"],"resources":["stream","subtitles"]}""",
        installedAtMillis = 0,
    )

    @Test
    fun `only the query profile's enabled addons are asked for streams and subtitles`() = runTest {
        val dao = FakeAddonDao(
            listOf(addon(1, "mine.example"), addon(1, "off.example", enabled = false), addon(2, "theirs.example")),
        )
        coEvery { client.fetchStreams(any(), any(), any()) } answers {
            listOf(StremioStream("https://cdn/${firstArg<String>().length}.mkv", "S", "", emptyMap()))
        }
        coEvery { client.fetchSubtitles(any(), any(), any()) } returns listOf(StremioSubtitle("1", "https://s/1.srt", "eng"))
        val useCase = ResolveStreamsUseCase(dao, Lazy { client }, unconfiguredUsenet(), UsenetStreamRegistry(), noStreamProcessing())

        val result = useCase(StreamQuery(1, MediaRef(603, MediaType.MOVIE), imdbId = "tt0133093", episode = null))

        assertEquals(listOf("mine.example"), result.streams.map { it.addonName })
        assertEquals(listOf("mine.example"), result.subtitles.map { it.addonName })
        coVerify(exactly = 0) { client.fetchStreams("https://theirs.example/manifest.json", any(), any()) }
        coVerify(exactly = 0) { client.fetchSubtitles("https://theirs.example/manifest.json", any(), any()) }
        coVerify(exactly = 0) { client.fetchStreams("https://off.example/manifest.json", any(), any()) }
    }
}
