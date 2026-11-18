package com.seastreams.tv.ui.screens.details

import androidx.compose.ui.focus.FocusRequester
import com.seastreams.tv.core.database.entity.WatchProgressEntity
import com.seastreams.tv.core.datastore.AppSettings
import com.seastreams.tv.core.datastore.SettingsDataStore
import com.seastreams.tv.data.details.TitleDetailsRepository
import com.seastreams.tv.domain.model.Episode
import com.seastreams.tv.domain.model.EpisodeRef
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.SeasonInfo
import com.seastreams.tv.domain.playback.EpisodeWatchMarker
import com.seastreams.tv.domain.playback.PlaybackProgressRecorder
import com.seastreams.tv.domain.playback.ShuffleMode
import com.seastreams.tv.domain.playback.ShuffleSession
import com.seastreams.tv.testutil.FakeWatchProgressDao
import com.seastreams.tv.testutil.testDetails
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock

@OptIn(ExperimentalCoroutinesApi::class)
class EpisodesViewModelTest {

    private val dao = FakeWatchProgressDao()
    private val titles = mockk<TitleDetailsRepository>()
    private val settings = mockk<SettingsDataStore>()
    private val show = testDetails(SHOW, MediaType.TV, seasons = listOf(SeasonInfo(1, "Season 1", 5), SeasonInfo(2, "Season 2", 3)))
    private val season1 = (1..5).map { episode(1, it) }
    private val season2 = (1..3).map { episode(2, it) }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { settings.settings } returns flowOf(AppSettings())
        coEvery { titles.episodes(SHOW, 1) } returns season1
        coEvery { titles.episodes(SHOW, 2) } returns season2
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun openViewModel(): EpisodesViewModel {
        val clock = Clock.systemUTC()
        val vm = EpisodesViewModel(titles, PlaybackProgressRecorder(dao, clock), settings, EpisodeWatchMarker(dao, clock))
        vm.open(PROFILE, show, "tt0000042")
        return vm
    }

    private fun EpisodesViewModel.watched(): Set<EpisodeRef> =
        (season1 + season2).map { it.ref }.filter { state.value.isWatched(it) }.toSet()

    @Test
    fun `toggle flips one episode and the menu label follows it`() = runTest {
        val vm = openViewModel()
        val episode = season1[2]
        assertEquals(MARK_WATCHED, menuToggleLabel(vm, episode))

        vm.toggleWatched(episode)
        assertEquals(setOf(episode.ref), vm.watched())
        assertFalse(vm.state.value.isUnwatched(episode.ref), "watched still is no longer blurred")
        assertEquals(MARK_UNWATCHED, menuToggleLabel(vm, episode))

        vm.toggleWatched(episode)
        assertTrue(vm.watched().isEmpty())
        assertTrue(vm.state.value.isUnwatched(episode.ref))
        assertEquals(MARK_WATCHED, menuToggleLabel(vm, episode))
    }

    @Test
    fun `toggle on a part-watched episode marks it watched`() = runTest {
        dao.rows.value = listOf(saved(1, 2, position = 300, duration = 1_000))
        val vm = openViewModel()

        vm.toggleWatched(season1[1])
        assertEquals(setOf(EpisodeRef(1, 2)), vm.watched())
    }

    @Test
    fun `season watched and unwatched apply to every episode of a mixed season only`() = runTest {
        dao.rows.value = listOf(saved(1, 1, 1_000, 1_000), saved(1, 3, 200, 1_000), saved(2, 1, 1_000, 1_000))
        val vm = openViewModel()

        vm.markSeason(1, watched = true)
        assertEquals(season1.map { it.ref }.toSet() + EpisodeRef(2, 1), vm.watched())

        vm.markSeason(1, watched = false)
        assertEquals(setOf(EpisodeRef(2, 1)), vm.watched())
        assertEquals(1, dao.rows.value.size)
    }

    @Test
    fun `season marks load a season that isn't on screen yet`() = runTest {
        val vm = openViewModel()
        vm.markSeason(2, watched = true)
        assertEquals(season2.map { it.ref }.toSet(), vm.watched())
    }

    @Test
    fun `previous in season from episode 1, mid-season and over a mixed range`() = runTest {
        dao.rows.value = listOf(saved(1, 2, 1_000, 1_000), saved(1, 4, 400, 1_000), saved(1, 5, 1_000, 1_000))
        val vm = openViewModel()

        vm.markThrough(season1[0], watched = true)
        assertEquals(setOf(EpisodeRef(1, 1), EpisodeRef(1, 2), EpisodeRef(1, 5)), vm.watched())

        vm.markThrough(season1[3], watched = true)
        assertEquals(season1.map { it.ref }.toSet(), vm.watched())

        vm.markThrough(season1[2], watched = false)
        assertEquals(setOf(EpisodeRef(1, 4), EpisodeRef(1, 5)), vm.watched())

        vm.markThrough(season1[0], watched = false)
        assertEquals(setOf(EpisodeRef(1, 4), EpisodeRef(1, 5)), vm.watched())
    }

    @Test
    fun `shuffle season only returns episodes of that season`() = runTest {
        val vm = openViewModel()
        val picks = (1..100).map { vm.shuffleTarget(2) }

        picks.forEach { target ->
            assertNotNull(target)
            assertEquals(2, target?.episode?.season)
            assertEquals(PROFILE, target?.profileId)
        }
        assertEquals(season2.map { it.ref }.toSet(), picks.mapNotNull { it?.episode }.toSet())
    }

    @Test
    fun `shuffle season starts a season-scoped shuffle, an episode click plays sequentially`() = runTest {
        val vm = openViewModel()
        val shuffled = vm.shuffleTarget(2)

        assertEquals(ShuffleMode.Season(2), shuffled?.shuffle?.mode)
        assertEquals(listOfNotNull(shuffled?.episode), shuffled?.shuffle?.played)
        assertEquals(ShuffleSession.Sequential, vm.target(season1[0])?.shuffle)
    }

    private fun menuToggleLabel(vm: EpisodesViewModel, episode: Episode): String =
        episodeMenu(episode, vm.state.value.isWatched(episode.ref), FocusRequester(), {}, {}, {}, {}).actions[2].label

    private fun episode(season: Int, number: Int) = Episode(EpisodeRef(season, number), "E$number", "", null, 40, null)

    private fun saved(season: Int, episode: Int, position: Long, duration: Long) = WatchProgressEntity(
        profileId = PROFILE,
        tmdbId = SHOW,
        mediaType = MediaType.TV,
        seasonNumber = season,
        episodeNumber = episode,
        title = "Show",
        episodeTitle = null,
        posterPath = null,
        backdropPath = null,
        positionMs = position,
        durationMs = duration,
        updatedAtMillis = 1L,
    )

    private companion object {
        const val PROFILE = 1L
        const val SHOW = 42
    }
}
