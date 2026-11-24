package com.seastreams.tv.domain.usecase

import com.seastreams.tv.domain.model.TrailerStream
import com.seastreams.tv.player.InlineTrailerController
import com.seastreams.tv.player.InlineTrailerPlayback
import com.seastreams.tv.player.InlineTrailerSettings
import com.seastreams.tv.player.InlineTrailerSlot
import com.seastreams.tv.player.InlineTrailerState
import com.seastreams.tv.testutil.testMedia
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PRD §9 *Runtime compatibility* (Fire OS / API < 33): an extractor `LinkageError` fails the trailer
 * without crashing, skips the remaining candidates, and inline previews keep their artwork.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrailerLinkageErrorTest {

    private val everything = VideoCodecSupport { _, _, _ -> true }
    private val keys = listOf("k1", "k2", "k3")
    private val attempts = mutableListOf<String>()
    private val noSuchMethod = NoSuchMethodError("No static method decode(Ljava/lang/String;Ljava/nio/charset/Charset;)")

    private fun TestScope.useCase(extractor: TrailerExtractor) =
        ResolveTrailerStreamUseCase(extractor, everything, StandardTestDispatcher(testScheduler))

    private val brokenRuntime = TrailerExtractor { key ->
        attempts += key
        throw noSuchMethod
    }

    private suspend fun failureOf(block: suspend () -> Unit): Throwable =
        try {
            block()
            throw AssertionError("expected a failure")
        } catch (e: AssertionError) {
            throw e
        } catch (e: Throwable) {
            e
        }

    @Test
    fun `a raw LinkageError from the extractor becomes an IOException and skips the other candidates`() = runTest {
        val failure = failureOf { useCase(brokenRuntime)(keys) }

        assertTrue(failure is TrailerExtractorUnavailableException, "got $failure")
        assertSame(noSuchMethod, failure.cause)
        assertEquals(listOf("k1"), attempts, "the runtime gap fails every key alike")
    }

    @Test
    fun `TrailerExtractorUnavailableException also stops after the first candidate`() = runTest {
        val extractor = TrailerExtractor { key ->
            attempts += key
            throw TrailerExtractorUnavailableException(key, noSuchMethod)
        }
        val failure = failureOf { useCase(extractor)(keys) }

        assertTrue(failure is TrailerExtractorUnavailableException, "got $failure")
        assertEquals(listOf("k1"), attempts)
    }

    @Test
    fun `cancelling a resolve still cancels`() = runTest {
        val hanging = TrailerExtractor { key ->
            attempts += key
            awaitCancellation()
        }
        val resolve = async { useCase(hanging)(keys) }
        runCurrent()
        resolve.cancel()
        runCurrent()

        assertTrue(resolve.isCancelled)
        assertEquals(listOf("k1"), attempts, "no further candidate after cancellation")
    }

    @Test
    fun `inline previews keep their artwork and never retry when the runtime can't extract`() = runTest {
        val dune = testMedia(1)
        val slot = InlineTrailerSlot("home/row-a", 0, dune.key)
        val started = mutableListOf<TrailerStream>()
        val playback = object : InlineTrailerPlayback {
            override suspend fun start(stream: TrailerStream, muted: Boolean, onReady: () -> Unit, onError: () -> Unit) {
                started += stream
            }

            override fun stop() = Unit
        }
        val resolve = useCase(brokenRuntime)
        // backgroundScope: an exception escaping the controller would fail this test.
        val trailers = InlineTrailerController(
            scope = backgroundScope,
            settings = { InlineTrailerSettings(enabled = true, muted = true, delayMs = 2_000) },
            trailerKeys = { keys },
            resolveStream = { candidates -> resolve(candidates, 720) },
            playback = playback,
        )

        trailers.onCardFocused(slot, dune)
        advanceTimeBy(2_001)
        runCurrent()

        assertTrue(started.isEmpty(), "nothing plays: the 16:9 slot keeps its static artwork")
        assertEquals(InlineTrailerState.Idle, trailers.state.value)
        assertEquals(listOf("k1"), attempts)

        trailers.onFocusLeft(slot)
        trailers.onCardFocused(slot, dune)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(listOf("k1"), attempts, "a failed title isn't tried again this session")
    }
}
