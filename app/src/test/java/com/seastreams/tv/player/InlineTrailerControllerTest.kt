package com.seastreams.tv.player

import com.seastreams.tv.domain.model.MediaRef
import com.seastreams.tv.domain.model.TrailerSource
import com.seastreams.tv.domain.model.TrailerStream
import com.seastreams.tv.testutil.testMedia
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class InlineTrailerControllerTest {

    private val dune = testMedia(1)
    private val alien = testMedia(2)

    // The same title (dune) is the first, expanded card on two adjacent rows.
    private val duneRowA = InlineTrailerSlot("home/row-a", 0, dune.key)
    private val alienRowA = InlineTrailerSlot("home/row-a", 1, alien.key)
    private val duneRowB = InlineTrailerSlot("home/row-b", 0, dune.key)
    private val stream = TrailerStream(TrailerSource("https://v"), TrailerSource("https://a"), 720)

    private val keyLookups = mutableListOf<MediaRef>()
    private var settings = InlineTrailerSettings(enabled = true, muted = true, delayMs = 2_000)
    private var extractionFails = false

    /** Records starts/stops; reports READY or an error on demand. */
    private inner class FakePlayback : InlineTrailerPlayback {
        val started = mutableListOf<TrailerStream>()
        var stops = 0
        var onReady: () -> Unit = {}
        var onError: () -> Unit = {}

        override suspend fun start(stream: TrailerStream, muted: Boolean, onReady: () -> Unit, onError: () -> Unit) {
            started += stream
            this.onReady = onReady
            this.onError = onError
        }

        override fun stop() {
            stops++
        }
    }

    private val playback = FakePlayback()

    private fun TestScope.controller() = InlineTrailerController(
        scope = backgroundScope,
        settings = { settings },
        trailerKey = { ref -> keyLookups += ref; "yt-${ref.tmdbId}" },
        resolveStream = { if (extractionFails) throw IOException("blocked") else stream },
        playback = playback,
    )

    @Test
    fun `the trailer starts only after focus rests for the configured delay`() = runTest {
        val trailers = controller()
        trailers.onCardFocused(duneRowA, dune)

        advanceTimeBy(1_999)
        runCurrent()
        assertTrue(playback.started.isEmpty())
        assertTrue(keyLookups.isEmpty(), "no TMDB lookup before the delay")

        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, playback.started.size)
        assertEquals(InlineTrailerState.Idle, trailers.state.value, "artwork stays until the player is READY")

        playback.onReady()
        assertEquals(InlineTrailerState.Playing(duneRowA, muted = true), trailers.state.value)
    }

    @Test
    fun `moving focus to another card cancels the pending hover and restarts the delay`() = runTest {
        val trailers = controller()
        trailers.onCardFocused(duneRowA, dune)
        advanceTimeBy(1_500)
        trailers.onCardFocused(alienRowA, alien)
        advanceTimeBy(1_500)
        runCurrent()
        assertTrue(playback.started.isEmpty())

        advanceTimeBy(600)
        runCurrent()
        assertEquals(listOf(alien.ref), keyLookups, "only the card focus rested on is looked up")
    }

    @Test
    fun `held-key scrolling, navigation and pause cancel immediately and stop playback`() = runTest {
        val trailers = controller()
        trailers.onCardFocused(duneRowA, dune)
        advanceTimeBy(2_001)
        runCurrent()
        playback.onReady()

        trailers.cancel()
        assertEquals(InlineTrailerState.Idle, trailers.state.value)
        assertEquals(1, playback.stops)

        trailers.onCardFocused(alienRowA, alien)
        trailers.cancel()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, playback.started.size, "the cancelled hover never starts")
    }

    @Test
    fun `focus leaving a card only cancels that card's preview`() = runTest {
        val trailers = controller()
        trailers.onCardFocused(alienRowA, alien)
        trailers.onFocusLeft(duneRowA) // a late blur from the previous card
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(1, playback.started.size)

        trailers.onFocusLeft(alienRowA)
        assertEquals(1, playback.stops)
    }

    @Test
    fun `the configured delay setting is honoured`() = runTest {
        settings = settings.copy(delayMs = 7_000)
        val trailers = controller()
        trailers.onCardFocused(duneRowA, dune)
        advanceTimeBy(6_999)
        runCurrent()
        assertTrue(playback.started.isEmpty())
        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, playback.started.size)
    }

    @Test
    fun `nothing happens with Auto-Play Trailers off`() = runTest {
        settings = settings.copy(enabled = false)
        val trailers = controller()
        trailers.onCardFocused(duneRowA, dune)
        advanceTimeBy(30_000)
        runCurrent()
        assertTrue(keyLookups.isEmpty())
        assertTrue(playback.started.isEmpty())
    }

    @Test
    fun `an extraction failure silently keeps the artwork and isn't retried`() = runTest {
        extractionFails = true
        val trailers = controller()
        trailers.onCardFocused(duneRowA, dune)
        advanceTimeBy(2_001)
        runCurrent()

        assertEquals(InlineTrailerState.Idle, trailers.state.value)
        assertTrue(playback.started.isEmpty(), "nothing is played, and there is no YouTube fallback path")

        trailers.onCardFocused(duneRowA, dune)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, keyLookups.size, "a failed title isn't looked up again")
    }

    @Test
    fun `a playback error reverts the card to its static artwork`() = runTest {
        val trailers = controller()
        trailers.onCardFocused(duneRowA, dune)
        advanceTimeBy(2_001)
        runCurrent()
        playback.onReady()
        assertEquals(InlineTrailerState.Playing(duneRowA, true), trailers.state.value)

        playback.onError()
        assertEquals(InlineTrailerState.Idle, trailers.state.value)
        assertEquals(1, playback.stops)
    }

    @Test
    fun `unmuted previews report it so no muted badge is drawn`() = runTest {
        settings = settings.copy(muted = false)
        val trailers = controller()
        trailers.onCardFocused(duneRowA, dune)
        advanceTimeBy(2_001)
        runCurrent()
        playback.onReady()
        assertEquals(InlineTrailerState.Playing(duneRowA, muted = false), trailers.state.value)
    }

    @Test
    fun `the same title on two rows plays only in the focused row's slot`() = runTest {
        val trailers = controller()
        trailers.onCardFocused(duneRowA, dune)
        advanceTimeBy(2_001)
        runCurrent()
        playback.onReady()

        val state = trailers.state.value
        assertEquals(InlineTrailerState.Playing(duneRowA, muted = true), state)
        assertTrue(state.playsIn(duneRowA, cardFocused = true), "the focused card shows the trailer")
        assertFalse(state.playsIn(duneRowB, cardFocused = false), "the wide duplicate below keeps its backdrop")
        assertFalse(state.playsIn(duneRowB, cardFocused = true), "a matching title alone never binds the surface")
    }

    @Test
    fun `the owning slot must also hold D-pad focus to bind the surface`() = runTest {
        val trailers = controller()
        trailers.onCardFocused(duneRowA, dune)
        advanceTimeBy(2_001)
        runCurrent()
        playback.onReady()

        assertFalse(trailers.state.value.playsIn(duneRowA, cardFocused = false))
        assertFalse(InlineTrailerState.Idle.playsIn(duneRowA, cardFocused = true))
    }

    @Test
    fun `moving onto the same title in the next row survives the previous row's late blur`() = runTest {
        val trailers = controller()
        trailers.onCardFocused(duneRowA, dune)
        advanceTimeBy(2_001)
        runCurrent()
        playback.onReady()

        trailers.onCardFocused(duneRowB, dune) // Down onto the duplicate
        trailers.onFocusLeft(duneRowA) // the top card's blur arrives afterwards
        assertEquals(1, playback.stops, "the top row's preview stopped once")
        assertEquals(InlineTrailerState.Idle, trailers.state.value)

        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(2, playback.started.size, "the lower row's hover was not cancelled by the stale blur")
        playback.onReady()
        val state = trailers.state.value
        assertEquals(InlineTrailerState.Playing(duneRowB, muted = true), state)
        assertTrue(state.playsIn(duneRowB, cardFocused = true))
        assertFalse(state.playsIn(duneRowA, cardFocused = false))
    }

    @Test
    fun `a late READY from the previous row's slot is ignored`() = runTest {
        val trailers = controller()
        trailers.onCardFocused(duneRowA, dune)
        advanceTimeBy(2_001)
        runCurrent()
        val readyFromRowA = playback.onReady

        trailers.onCardFocused(duneRowB, dune)
        readyFromRowA()
        assertEquals(InlineTrailerState.Idle, trailers.state.value)

        advanceTimeBy(2_001)
        runCurrent()
        readyFromRowA()
        assertEquals(InlineTrailerState.Idle, trailers.state.value, "row A's callback never claims row B's preview")
    }

    @Test
    fun `a held Left stopped on the first card restarts the hover delay on release`() = runTest {
        val trailers = controller()
        // Held Left lands on item 0 (it arms), then trailing repeats on the edge cancel it.
        trailers.onCardFocused(duneRowA, dune)
        trailers.cancel()
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(playback.started.isEmpty(), "held repeats keep the preview cancelled")

        // KeyUp: the row's HeldKeyTrailerGate re-arms item 0's hover delay.
        trailers.onCardFocused(duneRowA, dune)
        advanceTimeBy(1_999)
        runCurrent()
        assertTrue(playback.started.isEmpty())
        advanceTimeBy(2)
        runCurrent()
        playback.onReady()
        assertEquals(InlineTrailerState.Playing(duneRowA, muted = true), trailers.state.value)
    }
}
