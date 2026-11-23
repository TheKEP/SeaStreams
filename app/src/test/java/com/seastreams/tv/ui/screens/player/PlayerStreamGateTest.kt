package com.seastreams.tv.ui.screens.player

import com.seastreams.tv.data.usenet.newznab.NzbRequest
import com.seastreams.tv.domain.model.StreamSource
import com.seastreams.tv.player.usenet.UsenetPreflight
import com.seastreams.tv.usenet.archive.ArchiveRefusedException
import com.seastreams.tv.usenet.archive.ByteVolume
import com.seastreams.tv.usenet.archive.UsenetUnplayableException
import com.seastreams.tv.usenet.session.UsenetGrab
import com.seastreams.tv.usenet.session.UsenetSession
import com.seastreams.tv.usenet.session.UsenetSessionRegistry
import com.seastreams.tv.usenet.session.UsenetStreamRegistry
import com.seastreams.tv.usenet.verify.ReleaseHealth
import com.seastreams.tv.usenet.verify.ReleaseHealthCache
import dagger.Lazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import com.seastreams.tv.testutil.usenetSettings

/**
 * PRD §12 Playback inside the open player: a fallback advance or a Streams pick that lands on an unstreamable Usenet
 * release never hands it to ExoPlayer (only [PlayerStreamGate]'s `onPlay` would).
 */
class PlayerStreamGateTest {
    private val registry = UsenetStreamRegistry()
    private val health = ReleaseHealthCache()
    private val events = mutableListOf<String>()

    private fun usenet(title: String): StreamSource {
        val grab = UsenetGrab.Indexed(1, "Idx", title, NzbRequest("https://idx/get?id=$title", "k$title"), title, 1)
        return StreamSource("usenet://builtin", "Usenet", registry.register(grab), "Usenet", title, emptyMap())
    }

    private val sevenZip = usenet("A.7z")
    private val passworded = usenet("B.passworded")
    private val missing = usenet("C.census")
    private val good = usenet("D.good")
    private val http = StreamSource("https://addon", "Addon", "https://s/a.mkv", "Addon", "", emptyMap())

    private val sessions = UsenetSessionRegistry(
        opener = { token ->
            when (token) {
                token(sevenZip) -> throw ArchiveRefusedException("7z archives can't be streamed")
                token(passworded) -> throw ArchiveRefusedException("The archive is password-protected")
                token(missing) -> throw UsenetUnplayableException("Articles are missing")
                else -> UsenetSession(token, "Movie.mkv", ByteVolume("Movie.mkv", ByteArray(1_024)), emptyList(), 60_000)
            }
        },
        clock = { 0L },
        maxSessions = 4,
        reapPeriodMs = 0,
    )
    private val gate = PlayerStreamGate(UsenetPreflight(Lazy { sessions }, health, Dispatchers.Unconfined, usenetSettings(), UsenetStreamRegistry()))

    private fun token(stream: StreamSource) = checkNotNull(UsenetStreamRegistry.tokenOf(stream.url))

    @AfterEach
    fun tearDown() = sessions.close()

    private fun open(streams: List<StreamSource>, index: Int = 0, failover: Boolean = true) {
        gate.open(
            scope = TestScope(UnconfinedTestDispatcher()),
            streams = streams,
            index = index,
            failover = failover,
            onPlay = { events += "play $it" },
            onSkip = { from, to, total -> events += "skip $from→$to/$total" },
            onStopped = { events += "stopped $it" },
            onExhausted = { events += "exhausted" },
        )
    }

    @Test
    fun `refused releases are skipped in order and only the first playable one reaches the player`() {
        open(listOf(sevenZip, passworded, missing, good, http))
        assertEquals(listOf("skip 0→1/5", "skip 1→2/5", "skip 2→3/5", "play 3"), events)
        assertTrue(health.get(token(sevenZip)) is ReleaseHealth.NotStreamable)
        assertTrue(health.get(token(passworded)) is ReleaseHealth.NotStreamable)
    }

    @Test
    fun `an HTTP stream after a refused release goes straight to the player`() {
        open(listOf(http, sevenZip, http), index = 1)
        assertEquals(listOf("skip 1→2/3", "play 2"), events)
    }

    @Test
    fun `with failover off the gate stops on the refused release instead of advancing`() {
        open(listOf(sevenZip, good), failover = false)
        assertEquals(listOf("stopped 0"), events)
    }

    @Test
    fun `nothing left to try ends in exhausted, never a player error`() {
        open(listOf(good, sevenZip, passworded), index = 1)
        assertEquals(listOf("skip 1→2/3", "exhausted"), events)
    }
}
