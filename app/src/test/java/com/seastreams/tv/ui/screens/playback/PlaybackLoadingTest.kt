package com.seastreams.tv.ui.screens.playback

import com.seastreams.tv.testutil.noUsenetPreflight
import com.seastreams.tv.testutil.passThroughExternalUrls
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.seastreams.tv.core.datastore.SettingsDataStore
import com.seastreams.tv.domain.model.ContinueWatchingItem
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.PlaybackArtwork
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
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Clock

/**
 * PRD §9 Player Loading & Buffering UI, before the player opens: auto-play's stream lookup shows the
 * title's backdrop + logo, hands straight over to the player, and `Back` cancels it cleanly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackLoadingTest {

    @TempDir
    lateinit var dir: File

    private lateinit var storeScope: CoroutineScope
    private lateinit var settings: SettingsDataStore
    private val resolver = mockk<PlaybackStreamResolver>()
    private val resolveCard = mockk<ResolveContinueWatchingTargetUseCase>()
    private val plain = testDetails(tmdbId = 9)
    private val details = plain.copy(media = plain.media.copy(heroBackdropUrl = HERO, logoUrl = LOGO))
    private val target = PlaybackTarget(PROFILE_ID, details, "tt0000009", episode = null)
    private val artwork = PlaybackArtwork(HERO, LOGO, "Title 9")
    private val streams = listOf(StreamSource("https://addon", "Addon", "https://s/a", "Addon · a", "", emptyMap()))

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        settings = SettingsDataStore(PreferenceDataStoreFactory.create(scope = storeScope) { File(dir, "s.preferences_pb") })
        runBlocking {
            settings.setActiveProfileId(PROFILE_ID)
            settings.updatePlayer { it.copy(autoPlayTopStream = true, autoPlayFromContinueWatching = true) }
        }
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
        lastUsed = LastUsedStreamRecorder(FakeLastUsedStreamDao(), Clock.systemUTC()),
        resolveTrailer = mockk(),
        resolveContinueWatching = resolveCard,
        externalUrls = passThroughExternalUrls(),
        preflight = noUsenetPreflight(),
        clock = Clock.systemUTC(),
    )

    private fun resolved(list: List<StreamSource>) = ResolvedPlayback(target, StreamResolution(list, emptyList(), emptyList()), topIndex = 0)

    @Test
    fun `auto-play shows the backdrop while looking up, then hands over to the player in one update`() = runBlocking {
        val gate = CompletableDeferred<ResolvedPlayback>()
        coEvery { resolver.resolve(target, any()) } coAnswers { gate.await() }
        val vm = viewModel()

        vm.play(target, forcePicker = false)
        val looking = withTimeout(TIMEOUT_MS) { vm.state.first { it.loading != null } }
        assertEquals(artwork, looking.loading)
        assertNull(looking.player)

        gate.complete(resolved(streams))
        val playing = withTimeout(TIMEOUT_MS) { vm.state.first { it.player != null } }
        assertTrue(playing.player is PlaybackRequest.Media)
        assertNull(playing.loading, "the loading layer closes in the same state that opens the player")
        assertEquals(artwork, (playing.player as PlaybackRequest.Media).artwork, "the player keeps the same branding")
    }

    @Test
    fun `the stream list path never shows the loading screen`() = runBlocking {
        coEvery { resolver.resolve(target, any()) } returns resolved(streams)
        val vm = viewModel()
        val seen = mutableListOf<PlaybackArtwork?>()

        vm.play(target, forcePicker = true)
        withTimeout(TIMEOUT_MS) { vm.state.first { seen += it.loading; it.picker?.loading == false } }

        assertEquals(listOf<PlaybackArtwork?>(null), seen.distinct())
    }

    @Test
    fun `no streams closes the loading screen and says so`() = runBlocking {
        coEvery { resolver.resolve(target, any()) } returns resolved(emptyList())
        val vm = viewModel()

        vm.play(target, forcePicker = false)
        val event = withTimeout(TIMEOUT_MS) { vm.events.first() }

        assertEquals(PlaybackEvent.Message("No streams found"), event)
        assertEquals(PlaybackUiState(), vm.state.value)
    }

    @Test
    fun `Back during the lookup cancels it and never opens the player`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        coEvery { resolver.resolve(target, any()) } coAnswers {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }
        val vm = viewModel()

        vm.play(target, forcePicker = false)
        withTimeout(TIMEOUT_MS) { started.await() }
        assertEquals(artwork, vm.state.value.loading)
        vm.cancelLoading()

        withTimeout(TIMEOUT_MS) { cancelled.await() }
        assertEquals(PlaybackUiState(), vm.state.value)
    }

    @Test
    fun `Continue Watching shows the card backdrop at once, then the title's logo`() = runBlocking {
        val card = ContinueWatchingItem(plain.media.copy(heroBackdropUrl = HERO), 0, 0, 0.3f)
        val loaded = CompletableDeferred<ContinueWatchingTarget>()
        coEvery { resolveCard(PROFILE_ID, card) } coAnswers { loaded.await() }
        val lookup = CompletableDeferred<ResolvedPlayback>()
        coEvery { resolver.resolve(target, any()) } coAnswers { lookup.await() }
        val vm = viewModel()

        vm.playContinueWatching(PROFILE_ID, card)
        val first = withTimeout(TIMEOUT_MS) { vm.state.first { it.loading != null } }
        assertEquals(PlaybackArtwork(HERO, null, ""), first.loading, "backdrop only: no title text that would swap to a logo")

        loaded.complete(ContinueWatchingTarget.Ready(target))
        val branded = withTimeout(TIMEOUT_MS) { vm.state.first { it.loading?.logoUrl != null } }
        assertEquals(artwork, branded.loading)

        lookup.complete(resolved(streams))
        val playing = withTimeout(TIMEOUT_MS) { vm.state.first { it.player != null } }
        assertNull(playing.loading)
    }

    @Test
    fun `a restricted Continue Watching title closes the loading screen`() = runBlocking {
        val card = ContinueWatchingItem(plain.media, 0, 0, 0.3f)
        coEvery { resolveCard(PROFILE_ID, card) } returns ContinueWatchingTarget.Restricted
        val vm = viewModel()

        vm.playContinueWatching(PROFILE_ID, card)
        withTimeout(TIMEOUT_MS) { vm.events.first() }

        assertEquals(PlaybackUiState(), vm.state.value)
    }

    private companion object {
        const val PROFILE_ID = 1L
        const val HERO = "https://img/w1280/hero.jpg"
        const val LOGO = "https://img/w500/logo.png"
        const val TIMEOUT_MS = 5_000L
    }
}
