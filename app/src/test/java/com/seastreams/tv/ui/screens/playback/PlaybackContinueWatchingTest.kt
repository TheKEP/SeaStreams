package com.seastreams.tv.ui.screens.playback

import com.seastreams.tv.testutil.noStreamPreflight
import com.seastreams.tv.testutil.passThroughExternalUrls
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.seastreams.tv.core.database.entity.WatchProgressEntity
import com.seastreams.tv.core.datastore.SettingsDataStore
import com.seastreams.tv.domain.model.ContinueWatchingItem
import com.seastreams.tv.domain.model.EpisodeRef
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.PlaybackRequest
import com.seastreams.tv.domain.model.StreamSource
import com.seastreams.tv.domain.playback.LastUsedStreamRecorder
import com.seastreams.tv.domain.playback.PlaybackProgressRecorder
import com.seastreams.tv.domain.playback.PlaybackStreamResolver
import com.seastreams.tv.domain.playback.PlaybackTarget
import com.seastreams.tv.domain.playback.ResolvedPlayback
import com.seastreams.tv.domain.usecase.ContinueWatchingTarget
import com.seastreams.tv.domain.usecase.ResolveContinueWatchingTargetUseCase
import com.seastreams.tv.domain.usecase.StreamResolution
import com.seastreams.tv.testutil.FakeLastUsedStreamDao
import com.seastreams.tv.testutil.FakeWatchProgressDao
import com.seastreams.tv.testutil.testDetails
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Clock

/**
 * PRD §3 *Auto-play from Continue Watching*: a card goes straight to playback of its own episode — top
 * stream, preferred addon, saved position — and never through the stream list or Details.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackContinueWatchingTest {

    @TempDir
    lateinit var dir: File

    private lateinit var storeScope: CoroutineScope
    private lateinit var settings: SettingsDataStore
    private val resolver = mockk<PlaybackStreamResolver>()
    private val resolveCard = mockk<ResolveContinueWatchingTargetUseCase>()
    private val progressDao = FakeWatchProgressDao()
    private val details = testDetails(tmdbId = 9, type = MediaType.TV)
    private val item = ContinueWatchingItem(details.media, seasonNumber = 2, episodeNumber = 4, progress = 0.5f)
    private val target = PlaybackTarget(PROFILE_ID, details, "tt0000009", EpisodeRef(2, 4), "The One", 42)
    private val streams = listOf(stream("a"), stream("b"))

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        settings = SettingsDataStore(PreferenceDataStoreFactory.create(scope = storeScope) { File(dir, "s.preferences_pb") })
        runBlocking {
            settings.setActiveProfileId(PROFILE_ID)
            settings.updatePlayer {
                it.copy(autoPlayFromContinueWatching = true, autoPlayTopStream = false, preferredAutoPlayAddonUrl = PREFERRED)
            }
        }
        coEvery { resolver.resolve(target, PREFERRED) } returns
            ResolvedPlayback(target, StreamResolution(streams, emptyList(), emptyList()), topIndex = 1)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        storeScope.cancel()
    }

    private fun viewModel() = PlaybackViewModel(
        settings = settings,
        streams = resolver,
        recorder = PlaybackProgressRecorder(progressDao, Clock.systemUTC()),
        lastUsed = LastUsedStreamRecorder(FakeLastUsedStreamDao(), Clock.systemUTC()),
        resolveTrailer = mockk(),
        resolveContinueWatching = resolveCard,
        externalUrls = passThroughExternalUrls(),
        preflight = noStreamPreflight(),
        clock = Clock.systemUTC(),
    )

    @Test
    fun `the card's episode plays at once on the top stream from its saved position, with no stream list`() = runBlocking {
        coEvery { resolveCard(PROFILE_ID, item) } returns ContinueWatchingTarget.Ready(target)
        progressDao.upsert(progress(positionMs = 600_000, durationMs = 2_520_000))
        val vm = viewModel()
        val seenPicker = mutableListOf<StreamPickerState?>()

        vm.playContinueWatching(PROFILE_ID, item)
        val playing = withTimeout(TIMEOUT_MS) { vm.state.first { seenPicker += it.picker; it.player != null } }

        val request = playing.player as PlaybackRequest.Media
        assertEquals(target, request.target)
        assertEquals(1, request.streamIndex)
        assertEquals(600_000L, request.startPositionMs)
        assertNull(playing.busyLabel)
        assertEquals(listOf<StreamPickerState?>(null), seenPicker.distinct())
        coVerify(exactly = 1) { resolver.resolve(target, PREFERRED) }

        vm.closePlayer()
        assertEquals(PlaybackUiState(), vm.state.value)
    }

    @Test
    fun `a title the profile can't watch only shows a message`() = runBlocking {
        coEvery { resolveCard(PROFILE_ID, item) } returns ContinueWatchingTarget.Restricted
        val vm = viewModel()

        vm.playContinueWatching(PROFILE_ID, item)
        val event = withTimeout(TIMEOUT_MS) { vm.events.first() }

        assertEquals(PlaybackEvent.Message("This title isn't available on this profile."), event)
        assertEquals(PlaybackUiState(), vm.state.value)
        coVerify(exactly = 0) { resolver.resolve(any(), any()) }
    }

    @Test
    fun `a title that can't be loaded only shows a message`() = runBlocking {
        coEvery { resolveCard(PROFILE_ID, item) } returns ContinueWatchingTarget.Unavailable
        val vm = viewModel()

        vm.playContinueWatching(PROFILE_ID, item)
        val event = withTimeout(TIMEOUT_MS) { vm.events.first() }

        assertEquals(PlaybackEvent.Message("Couldn't load this title. Check the connection and try again."), event)
        assertEquals(PlaybackUiState(), vm.state.value)
    }

    private fun progress(positionMs: Long, durationMs: Long) = WatchProgressEntity(
        profileId = PROFILE_ID,
        tmdbId = 9,
        mediaType = MediaType.TV,
        seasonNumber = 2,
        episodeNumber = 4,
        title = "Title 9",
        episodeTitle = "The One",
        posterPath = null,
        backdropPath = null,
        positionMs = positionMs,
        durationMs = durationMs,
        updatedAtMillis = 1L,
    )

    private fun stream(id: String) = StreamSource("https://addon", "Addon", "https://s/$id", "Addon · $id", "", emptyMap())

    private companion object {
        const val PROFILE_ID = 1L
        const val PREFERRED = "https://preferred"
        const val TIMEOUT_MS = 5_000L
    }
}
