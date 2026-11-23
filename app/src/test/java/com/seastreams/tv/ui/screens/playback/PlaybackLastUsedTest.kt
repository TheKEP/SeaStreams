package com.seastreams.tv.ui.screens.playback

import com.seastreams.tv.testutil.noUsenetPreflight
import com.seastreams.tv.testutil.passThroughExternalUrls
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.seastreams.tv.core.datastore.PlayerChoice
import com.seastreams.tv.core.datastore.SettingsDataStore
import com.seastreams.tv.domain.model.EpisodeRef
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.PlaybackRequest
import com.seastreams.tv.domain.model.StreamAddon
import com.seastreams.tv.domain.model.StreamSource
import com.seastreams.tv.domain.playback.LastUsedStreamRecorder
import com.seastreams.tv.domain.playback.PlaybackProgressRecorder
import com.seastreams.tv.domain.playback.PlaybackStreamResolver
import com.seastreams.tv.domain.playback.PlaybackTarget
import com.seastreams.tv.domain.playback.ResolvedPlayback
import com.seastreams.tv.domain.playback.streamUrlFingerprint
import com.seastreams.tv.domain.usecase.StreamResolution
import com.seastreams.tv.testutil.FakeLastUsedStreamDao
import com.seastreams.tv.testutil.FakeWatchProgressDao
import com.seastreams.tv.testutil.testDetails
import io.mockk.coEvery
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

/** PRD §6 `LAST USED`: the stream list opens knowing it, and an external-player hand-off records it. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackLastUsedTest {

    @TempDir
    lateinit var dir: File

    private lateinit var storeScope: CoroutineScope
    private lateinit var settings: SettingsDataStore
    private val resolver = mockk<PlaybackStreamResolver>()
    private val dao = FakeLastUsedStreamDao()
    private val lastUsed = LastUsedStreamRecorder(dao, Clock.systemUTC())
    private val target = PlaybackTarget(PROFILE_ID, testDetails(tmdbId = 7), "tt0000007", episode = null)
    private val streams = listOf(stream("a"), stream("b"), stream("c"))
    private val addons = listOf(StreamAddon("https://addon", "Addon"), StreamAddon("https://empty", "Empty"))

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        settings = SettingsDataStore(PreferenceDataStoreFactory.create(scope = storeScope) { File(dir, "s.preferences_pb") })
        runBlocking { settings.setActiveProfileId(PROFILE_ID) }
        coEvery { resolver.resolve(any(), any()) } returns
            ResolvedPlayback(target, StreamResolution(streams, emptyList(), emptyList(), addons), topIndex = 0)
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
        lastUsed = lastUsed,
        resolveTrailer = mockk(),
        resolveContinueWatching = mockk(),
        externalUrls = passThroughExternalUrls(),
        preflight = noUsenetPreflight(),
        clock = Clock.systemUTC(),
    )

    @Test
    fun `the stream list carries the LAST USED index with its streams`() = runBlocking {
        lastUsed.record(target, stream("c"))
        val vm = viewModel()

        vm.play(target, forcePicker = true)
        val picker = withTimeout(TIMEOUT_MS) { vm.state.first { it.picker?.loading == false } }.picker

        assertEquals(streams, picker?.streams)
        assertEquals(addons, picker?.streamAddons)
        assertEquals(2, picker?.lastUsedIndex)
    }

    @Test
    fun `without a recorded stream the list has no LAST USED`() = runBlocking {
        val vm = viewModel()

        vm.play(target, forcePicker = true)
        val picker = withTimeout(TIMEOUT_MS) { vm.state.first { it.picker?.loading == false } }.picker

        assertNull(picker?.lastUsedIndex)
    }

    @Test
    fun `another profile's LAST USED is not shown`() = runBlocking {
        lastUsed.record(target.copy(profileId = 2L), stream("b"))
        val vm = viewModel()

        vm.play(target, forcePicker = true)
        val picker = withTimeout(TIMEOUT_MS) { vm.state.first { it.picker?.loading == false } }.picker

        assertNull(picker?.lastUsedIndex)
    }

    @Test
    fun `choosing a stream for an external player records it as LAST USED`() = runBlocking {
        settings.updatePlayer { it.copy(playerChoice = PlayerChoice.SystemChooser) }
        val vm = viewModel()

        vm.play(target, forcePicker = true)
        withTimeout(TIMEOUT_MS) { vm.state.first { it.picker?.loading == false } }
        vm.choose(streams[1])
        withTimeout(TIMEOUT_MS) { vm.events.first() }

        assertEquals(streamUrlFingerprint("https://s/b"), lastUsed.lastUsed(target)?.urlFingerprint)
    }

    @Test
    fun `Resume with Auto-play top stream starts the LAST USED stream, not the top one`() = runBlocking {
        settings.updatePlayer { it.copy(autoPlayTopStream = true, playerChoice = PlayerChoice.BuiltIn) }
        lastUsed.record(target, stream("c"))
        val vm = viewModel()

        vm.play(target, forcePicker = false)
        val request = withTimeout(TIMEOUT_MS) { vm.state.first { it.player != null } }.player as PlaybackRequest.Media

        assertEquals(2, request.streamIndex)
    }

    @Test
    fun `auto-play without a LAST USED starts the top stream`() = runBlocking {
        settings.updatePlayer { it.copy(autoPlayTopStream = true, playerChoice = PlayerChoice.BuiltIn) }
        coEvery { resolver.resolve(any(), any()) } returns
            ResolvedPlayback(target, StreamResolution(streams, emptyList(), emptyList(), addons), topIndex = 1)
        val vm = viewModel()

        vm.play(target, forcePicker = false)
        val request = withTimeout(TIMEOUT_MS) { vm.state.first { it.player != null } }.player as PlaybackRequest.Media

        assertEquals(1, request.streamIndex)
    }

    @Test
    fun `an episode's forced auto-play starts that episode's LAST USED stream`() = runBlocking {
        settings.updatePlayer { it.copy(autoPlayTopStream = false, playerChoice = PlayerChoice.BuiltIn) }
        val episode = PlaybackTarget(PROFILE_ID, testDetails(tmdbId = 9, type = MediaType.TV), "tt9", EpisodeRef(2, 4))
        coEvery { resolver.resolve(any(), any()) } returns
            ResolvedPlayback(episode, StreamResolution(streams, emptyList(), emptyList(), addons), topIndex = 0)
        lastUsed.record(episode, stream("b"))
        lastUsed.record(episode.copy(episode = EpisodeRef(1, 1)), stream("c"))
        val vm = viewModel()

        vm.play(episode, forcePicker = false, forceAutoPlay = true)
        val request = withTimeout(TIMEOUT_MS) { vm.state.first { it.player != null } }.player as PlaybackRequest.Media

        assertEquals(1, request.streamIndex)
    }

    @Test
    fun `a re-fetched list with re-signed URLs and new seeder counts still shows LAST USED`() = runBlocking {
        lastUsed.record(target, StreamSource("https://addon", "Addon", "https://s/b?sig=old", "Addon · 4k", "Movie.DV\n👤 12", emptyMap()))
        val refetched = listOf(
            StreamSource("https://addon", "Addon", "https://s/a?sig=new", "Addon · 4k", "Movie.HDR\n👤 40", emptyMap()),
            StreamSource("https://addon", "Addon", "https://s/b?sig=new", "Addon · 4k", "Movie.DV\n👤 15", emptyMap()),
        )
        coEvery { resolver.resolve(any(), any()) } returns
            ResolvedPlayback(target, StreamResolution(refetched, emptyList(), emptyList(), addons), topIndex = 0)
        val vm = viewModel()

        vm.play(target, forcePicker = true)
        val picker = withTimeout(TIMEOUT_MS) { vm.state.first { it.picker?.loading == false } }.picker

        assertEquals(1, picker?.lastUsedIndex)
    }

    private fun stream(id: String) = StreamSource("https://addon", "Addon", "https://s/$id", "Addon · $id", "file-$id", emptyMap())

    private companion object {
        const val PROFILE_ID = 1L
        const val TIMEOUT_MS = 5_000L
    }
}
