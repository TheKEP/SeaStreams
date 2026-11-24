package com.seastreams.tv.ui.screens.playback

import com.seastreams.tv.testutil.noStreamPreflight
import com.seastreams.tv.testutil.passThroughExternalUrls
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.seastreams.tv.core.datastore.SettingsDataStore
import com.seastreams.tv.domain.model.PlaybackRequest
import com.seastreams.tv.domain.model.TrailerSource
import com.seastreams.tv.domain.model.TrailerStream
import com.seastreams.tv.domain.playback.LastUsedStreamRecorder
import com.seastreams.tv.domain.playback.PlaybackProgressRecorder
import com.seastreams.tv.domain.playback.PlaybackStreamResolver
import com.seastreams.tv.domain.usecase.ResolveTrailerStreamUseCase
import com.seastreams.tv.domain.usecase.TrailerExtractor
import com.seastreams.tv.testutil.FakeLastUsedStreamDao
import com.seastreams.tv.testutil.FakeWatchProgressDao
import io.mockk.coEvery
import io.mockk.coVerify
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

    private fun viewModel(resolveTrailer: ResolveTrailerStreamUseCase = this.resolveTrailer) = PlaybackViewModel(
        settings = SettingsDataStore(PreferenceDataStoreFactory.create(scope = storeScope) { File(dir, "s.preferences_pb") }),
        streams = mockk<PlaybackStreamResolver>(),
        recorder = PlaybackProgressRecorder(dao, Clock.systemUTC()),
        lastUsed = LastUsedStreamRecorder(FakeLastUsedStreamDao(), Clock.systemUTC()),
        resolveTrailer = resolveTrailer,
        resolveContinueWatching = mockk(),
        externalUrls = passThroughExternalUrls(),
        preflight = noStreamPreflight(),
        clock = Clock.systemUTC(),
    )

    @Test
    fun `a resolved trailer opens the built-in player with a Trailer request`() = runTest {
        coEvery { resolveTrailer(listOf("key1", "key1b"), any()) } returns stream
        val vm = viewModel()

        vm.playTrailer("Dune", listOf("key1", "key1b"))

        val request = vm.state.value.player
        assertTrue(request is PlaybackRequest.Trailer)
        request as PlaybackRequest.Trailer
        assertEquals("Dune", request.title)
        assertEquals(stream, request.stream)
        assertNull(vm.state.value.busyLabel)
        assertTrue(dao.rows.value.isEmpty(), "starting a trailer records no watch progress")
    }

    @Test
    fun `every candidate failing falls back to the YouTube app with the top-ranked key`() = runTest {
        coEvery { resolveTrailer(listOf("key2", "key3", "key4"), any()) } throws IOException("YouTube changed")
        val vm = viewModel()

        vm.playTrailer("Dune", listOf("key2", "key3", "key4"))

        assertNull(vm.state.value.player)
        val event = withTimeout(1_000) { vm.events.first() }
        assertEquals(PlaybackEvent.OpenYouTube("key2"), event)
    }

    @Test
    fun `an extractor LinkageError on older Android falls back to YouTube instead of crashing`() = runTest {
        val attempts = mutableListOf<String>()
        val brokenRuntime = TrailerExtractor { key ->
            attempts += key
            throw NoSuchMethodError("No static method decode(Ljava/lang/String;Ljava/nio/charset/Charset;)")
        }
        val vm = viewModel(ResolveTrailerStreamUseCase(brokenRuntime, { _, _, _ -> true }, UnconfinedTestDispatcher()))

        vm.playTrailer("Dune", listOf("key5", "key6"))

        val event = withTimeout(1_000) { vm.events.first() }
        assertEquals(PlaybackEvent.OpenYouTube("key5"), event)
        assertNull(vm.state.value.player)
        assertNull(vm.state.value.busyLabel)
        assertEquals(listOf("key5"), attempts)
    }

    @Test
    fun `an empty candidate list does nothing`() = runTest {
        val vm = viewModel()
        vm.playTrailer("Dune", emptyList())
        assertNull(vm.state.value.player)
        assertNull(vm.state.value.busyLabel)
        coVerify(exactly = 0) { resolveTrailer(any(), any()) }
    }

    @Test
    fun `closing the player clears the request`() = runTest {
        coEvery { resolveTrailer(any(), any()) } returns stream
        val vm = viewModel()
        vm.playTrailer("Dune", listOf("key1"))
        vm.closePlayer()
        assertNull(vm.state.value.player)
    }
}
