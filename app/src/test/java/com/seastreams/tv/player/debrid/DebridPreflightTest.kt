package com.seastreams.tv.player.debrid

import com.seastreams.tv.data.debrid.DebridServiceKind
import com.seastreams.tv.data.debrid.api.DebridAuthException
import com.seastreams.tv.data.debrid.stream.DebridLinkResolver
import com.seastreams.tv.data.usenet.newznab.UsenetSearchTarget
import com.seastreams.tv.domain.model.StreamSource
import com.seastreams.tv.player.external.ExternalStreamUrls
import com.seastreams.tv.player.usenet.PreflightResult
import com.seastreams.tv.testutil.DebridLibraryRig
import com.seastreams.tv.testutil.FakeLibraryClient
import com.seastreams.tv.testutil.VirtualClock
import com.seastreams.tv.testutil.debridItem
import com.seastreams.tv.testutil.streamPreflight
import com.seastreams.tv.ui.screens.player.PlayerStreamGate
import com.seastreams.tv.ui.screens.playback.StartPick
import com.seastreams.tv.ui.screens.playback.firstPlayable
import dagger.Lazy
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

/**
 * PRD §14 *Playback*: a Debrid file's HTTPS link is resolved before any player sees it; a file deleted from the
 * account or a revoked key is skipped silently with failover on, and stops on that stream (Streams overlay) with it
 * off. External players get the HTTPS link itself.
 */
class DebridPreflightTest {

    private val movie = UsenetSearchTarget.Movie(null, "Inception", 2010)
    private val rd = FakeLibraryClient(
        DebridServiceKind.REAL_DEBRID,
        listOf(debridItem("t:gone", "Inception.2010.2160p.mkv"), debridItem("t:ok", "Inception.2010.1080p.mkv")),
        links = mutableMapOf("t:ok-file" to "https://dl.real-debrid.com/ok/Inception.mkv"),
    )
    private val events = mutableListOf<String>()

    private suspend fun TestScope.setUp(): Pair<DebridLibraryRig, List<StreamSource>> {
        val rig = DebridLibraryRig(VirtualClock(testScheduler))
        rig.add(rd)
        rig.enable()
        return rig to rig.search.search(movie).streams
    }

    private fun DebridLibraryRig.preflight() = DebridPreflight(Lazy { resolver })

    @Test
    fun `a listed file resolves once and its link is reused for 20 minutes`() = runTest {
        val (rig, streams) = setUp()
        val preflight = rig.preflight()

        assertEquals(PreflightResult.Playable, preflight.check(streams[1]))
        assertEquals(PreflightResult.Playable, preflight.check(streams[1]))
        assertEquals(1, rd.resolveCalls)
        advanceTimeBy(DebridLinkResolver.LINK_TTL_MILLIS)
        preflight.check(streams[1])
        assertEquals(2, rd.resolveCalls)
    }

    @Test
    fun `a deleted file is skipped and the provider's cached library is dropped`() = runTest {
        val (rig, streams) = setUp()

        assertTrue(rig.preflight().check(streams[0]) is PreflightResult.Skip)
        rig.search.search(movie)
        assertEquals(2, rd.libraryCalls)
    }

    @Test
    fun `a revoked key is skipped and marks the account Sign-in needed`() = runTest {
        val (rig, streams) = setUp()
        rd.resolveError = DebridAuthException()

        assertTrue(rig.preflight().check(streams[1]) is PreflightResult.Skip)
        assertTrue(rig.dao.rows.value.single().signInNeeded)
    }

    @Test
    fun `Debrid switched off or a token nobody registered is skipped`() = runTest {
        val (rig, streams) = setUp()
        val unknown = streams[1].copy(url = "seastreams-debrid://" + "0".repeat(32))
        assertTrue(rig.preflight().check(unknown) is PreflightResult.Skip)

        rig.settings.setEnabled(false)
        assertEquals(PreflightResult.Skip("Debrid is switched off in Settings"), rig.preflight().check(streams[1]))
    }

    @Test
    fun `with failover on auto-play passes over the deleted file to the next stream`() = runTest {
        val (rig, streams) = setUp()
        val pick = firstPlayable(streams, 0, failover = true, streamPreflight(debrid = rig.preflight())) { from, to, total -> events += "skip $from→$to/$total" }

        assertEquals(StartPick.Play(1), pick)
        assertEquals(listOf("skip 0→1/2"), events)
    }

    @Test
    fun `in the player, failover on advances past the deleted file and failover off stops on it`() = runTest {
        val (rig, streams) = setUp()
        val gate = PlayerStreamGate(streamPreflight(debrid = rig.preflight()))
        fun open(failover: Boolean) = gate.open(
            scope = TestScope(UnconfinedTestDispatcher(testScheduler)),
            streams = streams,
            index = 0,
            failover = failover,
            onPlay = { events += "play $it" },
            onSkip = { from, to, total -> events += "skip $from→$to/$total" },
            onStopped = { events += "stopped $it" },
            onExhausted = { events += "exhausted" },
        )

        assertTrue(gate.handles(streams[0].url))
        open(failover = true)
        assertEquals(listOf("skip 0→1/2", "play 1"), events)
        events.clear()
        open(failover = false)
        assertEquals(listOf("stopped 0"), events)
    }

    @Test
    fun `an external player gets the HTTPS link itself, and a deleted file fails its hand-off`() = runTest {
        val (rig, streams) = setUp()
        val urls = ExternalStreamUrls(mockk(relaxed = true), mockk(relaxed = true), Lazy { error("no Usenet") }, Dispatchers.Unconfined, Lazy { rig.resolver })

        val handed = urls.prepare(streams[1], "Inception")
        assertEquals("https://dl.real-debrid.com/ok/Inception.mkv", handed.url)
        assertTrue(handed.headers.isEmpty())
        assertThrows<IOException> { urls.prepare(streams[0], "Inception") }
    }

    @Test
    fun `the player's loader sees every refusal as an IOException`() = runTest {
        val (rig, streams) = setUp()
        val token = streams[0].url.removePrefix("seastreams-debrid://")
        assertThrows<IOException> { rig.resolver.resolveBlocking(token) }
        assertEquals("https://dl.real-debrid.com/ok/Inception.mkv", rig.resolver.resolveBlocking(streams[1].url.removePrefix("seastreams-debrid://")))
    }
}
