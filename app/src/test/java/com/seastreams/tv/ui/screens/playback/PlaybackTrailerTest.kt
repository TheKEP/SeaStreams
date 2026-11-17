package com.seastreams.tv.ui.screens.playback

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.seastreams.tv.core.datastore.SettingsDataStore
import com.seastreams.tv.domain.model.PlaybackRequest
import com.seastreams.tv.domain.model.TrailerSource
import com.seastreams.tv.domain.model.TrailerStream
import com.seastreams.tv.domain.playback.PlaybackProgressRecorder
import com.seastreams.tv.domain.playback.PlaybackStreamResolver
import com.seastreams.tv.domain.usecase.ResolveTrailerStreamUseCase
import com.seastreams.tv.testutil.FakeWatchProgressDao
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.time.Clock

/** Details `Trailer` (PRD §6/§9): built-in full-screen playback, YouTube only when extraction fails. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackTrailerTest {

    @TempDir
    lateinit var dir: File

    private lateinit var storeScope: CoroutineScope
    private val dao = FakeWatchProgressDao()
    private val resolveTrailer = mockk<ResolveTrailerStreamUseCase>()
    private val stream = TrailerStream(TrailerSource("https://v", "<MPD/>"), TrailerSource("https://a"), 2160)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        storeScope.cancel()
    }

    private fun viewModel() = PlaybackViewModel(
        settings = SettingsDataStore(PreferenceDataStoreFactory.create(scope = storeScope) { File(dir, "s.preferences_pb") }),
        streams = mockk<PlaybackStreamResolver>(),
        recorder = PlaybackProgressRecorder(dao, Clock.systemUTC()),
        resolveTrailer = resolveTrailer,
        clock = Clock.systemUTC(),
    )

    @Test
    fun `a resolved trailer opens the built-in player with a Trailer request`() = runTest {
        coEvery { resolveTrailer("key1", any()) } returns stream
        val vm = viewModel()

        vm.playTrailer("Dune", "key1")

        val request = vm.state.value.player
        assertTrue(request is PlaybackRequest.Trailer)
        request as PlaybackRequest.Trailer
        assertEquals("Dune", request.title)
        assertEquals(stream, request.stream)
        assertNull(vm.state.value.busyLabel)
        assertTrue(dao.rows.value.isEmpty(), "starting a trailer records no watch progress")
    }

    @Test
    fun `an extraction error falls back to the YouTube app and opens no player`() = runTest {
        coEvery { resolveTrailer("key2", any()) } throws IOException("YouTube changed")
        val vm = viewModel()

        vm.playTrailer("Dune", "key2")

        assertNull(vm.state.value.player)
        val event = withTimeout(1_000) { vm.events.first() }
        assertEquals(PlaybackEvent.OpenYouTube("key2"), event)
    }

    @Test
    fun `closing the player clears the request`() = runTest {
        coEvery { resolveTrailer(any(), any()) } returns stream
        val vm = viewModel()
        vm.playTrailer("Dune", "key1")
        vm.closePlayer()
        assertNull(vm.state.value.player)
    }
}
