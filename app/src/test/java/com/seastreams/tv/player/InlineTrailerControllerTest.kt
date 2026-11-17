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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class InlineTrailerControllerTest {

    private val dune = testMedia(1)
    private val alien = testMedia(2)
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
        trailers.onCardFocused(dune)

        advanceTimeBy(1_999)
        runCurrent()
        assertTrue(playback.started.isEmpty())
        assertTrue(keyLookups.isEmpty(), "no TMDB lookup before the delay")

        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, playback.started.size)
        assertEquals(InlineTrailerState.Idle, trailers.state.value, "artwork stays until the player is READY")

        playback.onReady()
        assertEquals(InlineTrailerState.Playing(dune.key, muted = true), trailers.state.value)
    }

    @Test
    fun `moving focus to another card cancels the pending hover and restarts the delay`() = runTest {
        val trailers = controller()
        trailers.onCardFocused(dune)
        advanceTimeBy(1_500)
        trailers.onCardFocused(alien)
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
        trailers.onCardFocused(dune)
        advanceTimeBy(2_001)
        runCurrent()
        playback.onReady()

        trailers.cancel()
        assertEquals(InlineTrailerState.Idle, trailers.state.value)
        assertEquals(1, playback.stops)

        trailers.onCardFocused(alien)
        trailers.cancel()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, playback.started.size, "the cancelled hover never starts")
    }

    @Test
    fun `focus leaving a card only cancels that card's preview`() = runTest {
        val trailers = controller()
        trailers.onCardFocused(alien)
        trailers.onFocusLeft(dune.key) // a late blur from the previous card
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(1, playback.started.size)

        trailers.onFocusLeft(alien.key)
        assertEquals(1, playback.stops)
    }

    @Test
    fun `the configured delay setting is honoured`() = runTest {
        settings = settings.copy(delayMs = 7_000)
        val trailers = controller()
        trailers.onCardFocused(dune)
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
        trailers.onCardFocused(dune)
        advanceTimeBy(30_000)
        runCurrent()
        assertTrue(keyLookups.isEmpty())
        assertTrue(playback.started.isEmpty())
    }

    @Test
    fun `an extraction failure silently keeps the artwork and isn't retried`() = runTest {
        extractionFails = true
        val trailers = controller()
        trailers.onCardFocused(dune)
        advanceTimeBy(2_001)
        runCurrent()

        assertEquals(InlineTrailerState.Idle, trailers.state.value)
        assertTrue(playback.started.isEmpty(), "nothing is played, and there is no YouTube fallback path")

        trailers.onCardFocused(dune)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, keyLookups.size, "a failed title isn't looked up again")
    }

    @Test
    fun `a playback error reverts the card to its static artwork`() = runTest {
        val trailers = controller()
        trailers.onCardFocused(dune)
        advanceTimeBy(2_001)
        runCurrent()
        playback.onReady()
        assertEquals(InlineTrailerState.Playing(dune.key, true), trailers.state.value)

        playback.onError()
        assertEquals(InlineTrailerState.Idle, trailers.state.value)
        assertEquals(1, playback.stops)
    }

    @Test
    fun `unmuted previews report it so no muted badge is drawn`() = runTest {
        settings = settings.copy(muted = false)
        val trailers = controller()
        trailers.onCardFocused(dune)
        advanceTimeBy(2_001)
        runCurrent()
        playback.onReady()
        assertEquals(InlineTrailerState.Playing(dune.key, muted = false), trailers.state.value)
    }
}
