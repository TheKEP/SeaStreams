package com.seastreams.tv.ui.screens.playback

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.seastreams.tv.core.datastore.SettingsDataStore
import com.seastreams.tv.domain.model.PlaybackRequest
import com.seastreams.tv.domain.model.StreamSource
import com.seastreams.tv.domain.playback.PlaybackProgressRecorder
import com.seastreams.tv.domain.playback.PlaybackStreamResolver
import com.seastreams.tv.domain.playback.PlaybackTarget
import com.seastreams.tv.domain.playback.ResolvedPlayback
import com.seastreams.tv.domain.usecase.ResolveTrailerStreamUseCase
import com.seastreams.tv.domain.usecase.StreamResolution
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
 * Details `Play` → stream list → `Back` → `Play` (PRD §6): dismissing the list leaves no state
 * behind, so the very next `Play` does the normal action with auto-play top stream off and on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackPickerDismissTest {

    @TempDir
    lateinit var dir: File

    private lateinit var storeScope: CoroutineScope
    private lateinit var settings: SettingsDataStore
    private val resolver = mockk<PlaybackStreamResolver>()
    private val target = PlaybackTarget(PROFILE_ID, testDetails(tmdbId = 7), "tt0000007", episode = null)
    private val streams = listOf(stream("a"), stream("b"))

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        settings = SettingsDataStore(PreferenceDataStoreFactory.create(scope = storeScope) { File(dir, "s.preferences_pb") })
        runBlocking { settings.setActiveProfileId(PROFILE_ID) }
        coEvery { resolver.resolve(any(), any()) } returns
            ResolvedPlayback(target, StreamResolution(streams, emptyList(), emptyList()), topIndex = 0)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        storeScope.cancel()
    }

    private fun viewModel() = PlaybackViewModel(
        settings = settings,
        streams = resolver,
        recorder = PlaybackProgressRecorder(FakeWatchProgressDao(), Clock.systemUTC()),
        resolveTrailer = mockk<ResolveTrailerStreamUseCase>(),
        clock = Clock.systemUTC(),
    )

    private suspend fun PlaybackViewModel.awaitState(predicate: (PlaybackUiState) -> Boolean): PlaybackUiState =
        withTimeout(TIMEOUT_MS) { state.first(predicate) }

    @Test
    fun `auto-play off - the first Play after Back reopens the stream list`() = runBlocking {
        settings.updatePlayer { it.copy(autoPlayTopStream = false) }
        val vm = viewModel()

        vm.play(target, forcePicker = false)
        vm.awaitState { it.picker?.loading == false }
        vm.dismissPicker()
        assertNull(vm.state.value.picker)
        assertNull(vm.state.value.busyLabel)

        vm.play(target, forcePicker = false)
        val reopened = vm.awaitState { it.picker?.loading == false }
        assertEquals(streams, reopened.picker?.streams)
        assertNull(reopened.player)
        coVerify(exactly = 2) { resolver.resolve(any(), any()) }
    }

    @Test
    fun `auto-play on - the first Play after Back from the long-press list plays the top stream`() = runBlocking {
        settings.updatePlayer { it.copy(autoPlayTopStream = true) }
        val vm = viewModel()

        vm.play(target, forcePicker = true)
        vm.awaitState { it.picker?.loading == false }
        vm.dismissPicker()
        assertNull(vm.state.value.picker)

        vm.play(target, forcePicker = false)
        val playing = vm.awaitState { it.player != null }
        val request = playing.player as PlaybackRequest.Media
        assertEquals(0, request.streamIndex)
        assertEquals(streams, request.streams)
        assertNull(playing.picker)
        assertNull(playing.busyLabel)
        coVerify(exactly = 2) { resolver.resolve(any(), any()) }
    }

    private fun stream(id: String) = StreamSource("https://addon", "Addon", "https://s/$id", "Addon · $id", "", emptyMap())

    private companion object {
        const val PROFILE_ID = 1L
        const val TIMEOUT_MS = 5_000L
    }
}
