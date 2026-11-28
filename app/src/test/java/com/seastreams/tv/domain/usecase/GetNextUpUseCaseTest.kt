package com.seastreams.tv.domain.usecase

import com.seastreams.tv.core.database.entity.LibraryItemEntity
import com.seastreams.tv.core.database.entity.NextUpDismissalEntity
import com.seastreams.tv.core.database.entity.WatchProgressEntity
import com.seastreams.tv.core.datastore.SettingsDataStore
import com.seastreams.tv.data.details.TitleDetailsRepository
import com.seastreams.tv.domain.filter.AgeRestrictionEngine
import com.seastreams.tv.domain.model.EpisodeRef
import com.seastreams.tv.domain.model.MediaRef
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.SeasonInfo
import com.seastreams.tv.testutil.FakeLibraryDao
import com.seastreams.tv.testutil.FakeNextUpDismissalDao
import com.seastreams.tv.testutil.FakeWatchProgressDao
import com.seastreams.tv.testutil.InMemoryPreferences
import com.seastreams.tv.testutil.testDetails
import com.seastreams.tv.testutil.testProfile
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Milestone 13: Next Up cards for watched and Library shows, age-checked, without `/external_ids`. */
class GetNextUpUseCaseTest {

    private val library = FakeLibraryDao()
    private val progress = FakeWatchProgressDao(library)
    private val dismissals = FakeNextUpDismissalDao()
    private val titles = mockk<TitleDetailsRepository>()
    private val settings = SettingsDataStore(InMemoryPreferences())
    private val clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC)
    private val useCase = GetNextUpUseCase(progress, dismissals, titles, AgeRestrictionEngine(), settings, clock)

    private val adult = testProfile(1, isKids = false)
    private val seasons = listOf(SeasonInfo(1, "Season 1", 10), SeasonInfo(2, "Season 2", 8))

    @BeforeEach
    fun setUp() = runTest {
        settings.setActiveProfileId(adult.id)
        coEvery { titles.load(any(), null) } answers {
            val ref = firstArg<MediaRef>()
            testDetails(ref.tmdbId, MediaType.TV, seasons).copy(lastAired = EpisodeRef(2, 8))
        }
    }

    private suspend fun watched(tmdbId: Int, season: Int, episode: Int, updated: Long, position: Long = 95, profile: Long = 1) =
        progress.upsert(
            WatchProgressEntity(profile, tmdbId, MediaType.TV, season, episode, "Show $tmdbId", null, null, null, position, 100, updated),
        )

    private suspend fun cards(profile: Long = 1, kids: Boolean = false) =
        useCase.observe(testProfile(profile, kids)).first().map { Triple(it.media.tmdbId, it.seasonNumber, it.episodeNumber) }

    @Test
    fun `a finished episode of a show outside the Library surfaces the next one at 0 percent`() = runTest {
        watched(10, 1, 3, updated = 100)
        watched(11, 1, 10, updated = 200)

        val items = useCase.observe(adult).first()

        assertEquals(listOf(Triple(11, 2, 1), Triple(10, 1, 4)), items.map { Triple(it.media.tmdbId, it.seasonNumber, it.episodeNumber) })
        assertTrue(items.all { it.isNextUp && it.progress == 0f })
        assertEquals(EpisodeRef(2, 8), items.first().nextUp?.lastAired)
        coVerify(exactly = 0) { titles.imdbIdForDetails(any()) }
    }

    @Test
    fun `an unfinished newest episode and a caught-up show get no Next Up card`() = runTest {
        watched(10, 1, 3, updated = 100)
        watched(10, 1, 4, updated = 200, position = 40)
        watched(12, 2, 8, updated = 300)

        assertEquals(emptyList<Triple<Int, Int, Int>>(), cards())
    }

    @Test
    fun `an unwatched Library show surfaces nothing, an old watched one is always checked`() = runTest {
        library.insert(LibraryItemEntity(1, 50, MediaType.TV, "Saved", null, null, "", 1))
        library.insert(LibraryItemEntity(1, 51, MediaType.TV, "Saved and watched", null, null, "", 1))
        watched(51, 1, 2, updated = 1)
        (100..130).forEach { watched(it, 2, 8, updated = 1_000L + it) }

        assertEquals(listOf(Triple(51, 1, 3)), cards())
    }

    @Test
    fun `Kids profiles only get shows within their ceiling, and unloadable shows are left out`() = runTest {
        watched(20, 1, 1, updated = 100, profile = 2)
        watched(21, 1, 1, updated = 200, profile = 2)
        watched(22, 1, 1, updated = 300, profile = 2)
        coEvery { titles.load(MediaRef(20, MediaType.TV), null) } returns
            testDetails(20, MediaType.TV, seasons).let { it.copy(media = it.media.copy(certification = "TV-Y"), lastAired = EpisodeRef(2, 8)) }
        coEvery { titles.load(MediaRef(21, MediaType.TV), null) } returns
            testDetails(21, MediaType.TV, seasons).let { it.copy(media = it.media.copy(certification = "TV-MA"), lastAired = EpisodeRef(2, 8)) }
        coEvery { titles.load(MediaRef(22, MediaType.TV), null) } throws IOException("offline")

        assertEquals(listOf(Triple(20, 1, 2)), cards(profile = 2, kids = true))
    }

    @Test
    fun `a removed card stays hidden until the show is watched again`() = runTest {
        watched(10, 1, 3, updated = 100)
        dismissals.upsert(NextUpDismissalEntity(1, 10, 1, 4, 2, 8, dismissedAtMillis = 150))
        assertEquals(emptyList<Triple<Int, Int, Int>>(), cards())

        watched(10, 1, 4, updated = 200)
        assertEquals(listOf(Triple(10, 1, 5)), cards())
    }

    @Test
    fun `turning the setting off empties Next Up`() = runTest {
        watched(10, 1, 3, updated = 100)
        settings.updatePlayer { it.copy(showNextUpInContinueWatching = false) }

        assertEquals(emptyList<Triple<Int, Int, Int>>(), cards())
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
