package com.seastreams.tv.ui.screens.playback

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.seastreams.tv.core.datastore.PlayerChoice
import com.seastreams.tv.core.datastore.SettingsDataStore
import com.seastreams.tv.data.usenet.newznab.NzbRequest
import com.seastreams.tv.domain.model.PlaybackRequest
import com.seastreams.tv.domain.model.StreamSource
import com.seastreams.tv.domain.playback.LastUsedStreamRecorder
import com.seastreams.tv.domain.playback.PlaybackProgressRecorder
import com.seastreams.tv.domain.playback.PlaybackStreamResolver
import com.seastreams.tv.domain.playback.PlaybackTarget
import com.seastreams.tv.domain.playback.ResolvedPlayback
import com.seastreams.tv.domain.usecase.StreamResolution
import com.seastreams.tv.player.usenet.UsenetPreflight
import com.seastreams.tv.testutil.FakeLastUsedStreamDao
import com.seastreams.tv.testutil.FakeWatchProgressDao
import com.seastreams.tv.testutil.passThroughExternalUrls
import com.seastreams.tv.testutil.testDetails
import com.seastreams.tv.usenet.archive.ArchiveRefusedException
import com.seastreams.tv.usenet.archive.ByteVolume
import com.seastreams.tv.usenet.session.UsenetGrab
import com.seastreams.tv.usenet.session.UsenetSession
import com.seastreams.tv.usenet.session.UsenetSessionRegistry
import com.seastreams.tv.usenet.session.UsenetStreamRegistry
import com.seastreams.tv.usenet.verify.ReleaseHealth
import com.seastreams.tv.usenet.verify.ReleaseHealthCache
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
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
import com.seastreams.tv.testutil.usenetSettings

/**
 * PRD §12 Playback: unstreamable Usenet releases (a 7z set, a passworded RAR) are opened and refused before any
 * player exists, recorded in [ReleaseHealthCache], and auto-play moves silently to the first playable stream.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackUsenetSkipTest {

    @TempDir
    lateinit var dir: File

    private lateinit var storeScope: CoroutineScope
    private lateinit var settings: SettingsDataStore
    private val resolver = mockk<PlaybackStreamResolver>()
    private val target = PlaybackTarget(PROFILE_ID, testDetails(tmdbId = 9), "tt0000009", episode = null)
    private val streams = UsenetStreamRegistry()
    private val health = ReleaseHealthCache()
    private val opened = mutableListOf<String>()

    private fun usenet(title: String): StreamSource {
        val grab = UsenetGrab.Indexed(1, "Idx", title, NzbRequest("https://idx/get?id=$title", "k$title"), title, 1)
        return StreamSource("usenet://builtin", "Usenet", streams.register(grab), "Usenet · 1080p", title, emptyMap())
    }

    private val sevenZip = usenet("Movie.2024.1080p-7Z")
    private val passworded = usenet("Movie.2024.2160p-PW")
    private val playable = usenet("Movie.2024.720p")
    private val http = StreamSource("https://addon", "Addon", "https://s/a.mkv", "Addon · a", "", emptyMap())

    /** The opener a real session would use: 7z and passworded releases are refused like the archive layer does. */
    private val registry = UsenetSessionRegistry(
        opener = { token ->
            synchronized(opened) { opened += token }
            when (token) {
                token(sevenZip) -> throw ArchiveRefusedException("7z archives can't be streamed")
                token(passworded) -> throw ArchiveRefusedException("The archive is password-protected")
                else -> UsenetSession(token, "Movie.mkv", ByteVolume("Movie.mkv", ByteArray(4_096)), emptyList(), 60_000)
            }
        },
        clock = { 0L },
        maxSessions = 4,
        reapPeriodMs = 0,
    )
    private val preflight = UsenetPreflight(Lazy { registry }, health, Dispatchers.Unconfined, usenetSettings(), UsenetStreamRegistry())

    private fun token(stream: StreamSource) = checkNotNull(UsenetStreamRegistry.tokenOf(stream.url))

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        settings = SettingsDataStore(PreferenceDataStoreFactory.create(scope = storeScope) { File(dir, "s.preferences_pb") })
        runBlocking {
            settings.setActiveProfileId(PROFILE_ID)
            settings.updatePlayer { it.copy(autoPlayTopStream = true, autoStreamFailover = true) }
        }
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        storeScope.cancel()
        registry.close()
    }

    private fun viewModel() = PlaybackViewModel(
        settings = settings,
        streams = resolver,
        recorder = PlaybackProgressRecorder(FakeWatchProgressDao(), Clock.systemUTC()),
        lastUsed = LastUsedStreamRecorder(FakeLastUsedStreamDao(), Clock.systemUTC()),
        resolveTrailer = mockk(),
        resolveContinueWatching = mockk(),
        externalUrls = passThroughExternalUrls(),
        preflight = preflight,
        clock = Clock.systemUTC(),
    )

    private fun resolve(list: List<StreamSource>) {
        coEvery { resolver.resolve(target, any()) } returns ResolvedPlayback(target, StreamResolution(list, emptyList(), emptyList()), topIndex = 0)
    }

    @Test
    fun `refused releases are skipped before the player opens, recorded, and the first playable one plays`() = runBlocking {
        resolve(listOf(sevenZip, passworded, playable, http))
        val vm = viewModel()
        val states = mutableListOf<PlaybackUiState>()
        val collector = launch(Dispatchers.Unconfined) { vm.state.toList(states) }

        vm.play(target, forcePicker = false)
        val open = withTimeout(TIMEOUT_MS) { vm.state.first { it.player != null } }
        collector.cancel()

        assertEquals(2, (open.player as PlaybackRequest.Media).streamIndex, "the player is only ever given stream 3")
        assertEquals(1, states.mapNotNull { it.player }.distinct().size, "no player request for a refused release")
        assertTrue(states.any { it.loadingStatus == "Stream 1 didn't start. Trying stream 2 of 4…" })
        assertTrue(states.any { it.loadingStatus == "Stream 2 didn't start. Trying stream 3 of 4…" })
        assertNull(open.loadingStatus)
        assertTrue(health.get(token(sevenZip)) is ReleaseHealth.NotStreamable)
        assertTrue(health.get(token(passworded)) is ReleaseHealth.NotStreamable)
        assertTrue(registry.isOpen(token(playable)), "the player's first read reuses the session opened here")
    }

    @Test
    fun `a release already known bad is skipped without grabbing its NZB again`() = runBlocking {
        health.record(token(sevenZip), ReleaseHealth.NotStreamable("7z"))
        resolve(listOf(sevenZip, http))
        val vm = viewModel()

        vm.play(target, forcePicker = false)
        val open = withTimeout(TIMEOUT_MS) { vm.state.first { it.player != null } }

        assertEquals(1, (open.player as PlaybackRequest.Media).streamIndex)
        assertEquals(emptyList<String>(), opened, "no session open, so no t=get")
    }

    @Test
    fun `when every stream is refused, nothing opens and auto-play says so`() = runBlocking {
        resolve(listOf(sevenZip, passworded))
        val vm = viewModel()

        vm.play(target, forcePicker = false)
        val event = withTimeout(TIMEOUT_MS) { vm.events.first() }

        assertEquals(PlaybackEvent.Message(NONE_WOULD_PLAY), event)
        assertNull(vm.state.value.player)
        assertNull(vm.state.value.loading)
    }

    @Test
    fun `with failover off the chosen stream goes to the player, whose gate stops on it and opens Streams`() = runBlocking {
        settings.updatePlayer { it.copy(autoStreamFailover = false) }
        resolve(listOf(sevenZip, playable))
        val vm = viewModel()

        vm.play(target, forcePicker = false)
        val open = withTimeout(TIMEOUT_MS) { vm.state.first { it.player != null } }

        assertEquals(0, (open.player as PlaybackRequest.Media).streamIndex, "no silent skip with failover off")
        assertEquals(emptyList<String>(), opened, "nothing is skipped here: PlayerStreamGate refuses it inside the player")
    }

    @Test
    fun `external players skip refused releases the same way before the hand-off`() = runBlocking {
        settings.updatePlayer { it.copy(playerChoice = PlayerChoice.SystemChooser) }
        resolve(listOf(sevenZip, playable))
        val vm = viewModel()

        vm.play(target, forcePicker = false)
        val launch = withTimeout(TIMEOUT_MS) { vm.events.first { it is PlaybackEvent.Launch } } as PlaybackEvent.Launch

        assertEquals(playable.url, launch.spec.stream.url)
        assertTrue(health.get(token(sevenZip)) is ReleaseHealth.NotStreamable)
    }

    private companion object {
        const val PROFILE_ID = 1L
        const val TIMEOUT_MS = 5_000L
    }
}
