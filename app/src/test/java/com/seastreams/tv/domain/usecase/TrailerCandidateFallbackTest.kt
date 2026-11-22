package com.seastreams.tv.domain.usecase

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

/**
 * PRD §9 Trailer Candidates: up to 3 ranked TMDB keys are tried in turn, e.g. Toy Story 5's newest
 * official trailer is a made-for-kids upload YouTube won't serve while its older one plays.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrailerCandidateFallbackTest {

    private val everything = VideoCodecSupport { _, _, _ -> true }
    private val attempted = mutableListOf<String>()

    private fun playable(height: Int) = ExtractedTrailer(
        videos = listOf(TrailerVideoCandidate("https://v/$height", height, 30, height * 1_000, "avc1.640028", TrailerContainer.MP4, videoOnly = true, dashManifest = null)),
        audios = listOf(TrailerAudioCandidate("https://a", 128_000, TrailerContainer.M4A, dashManifest = null)),
    )

    private val hdrOnly = ExtractedTrailer(
        videos = listOf(TrailerVideoCandidate("https://hdr", 2160, 30, 1, "vp09.02.51.10.01.09.16.09.00", TrailerContainer.WEBM, videoOnly = true, dashManifest = null)),
        audios = listOf(TrailerAudioCandidate("https://a", 128_000, TrailerContainer.M4A, dashManifest = null)),
    )

    /** [results] per key: an [ExtractedTrailer] to return, or an [IOException] to throw. */
    private fun TestScope.useCase(results: Map<String, Any>) = ResolveTrailerStreamUseCase(
        extractor = { key ->
            attempted += key
            when (val result = results[key]) {
                is ExtractedTrailer -> result
                is IOException -> throw result
                else -> error("unexpected key $key")
            }
        },
        codecs = everything,
        dispatcher = StandardTestDispatcher(testScheduler),
    )

    private fun unplayable(key: String) = IOException("YouTube extraction failed for $key: UNPLAYABLE")

    @Test
    fun `a failed first candidate falls through to the second, and the third is never extracted`() = runTest {
        val resolve = useCase(mapOf("kids" to unplayable("kids"), "official" to playable(2160), "older" to playable(1080)))

        val stream = resolve(listOf("kids", "official", "older"))

        assertEquals(2160, stream.height)
        assertEquals(listOf("kids", "official"), attempted)
    }

    @Test
    fun `a candidate with nothing playable (all HDR) also falls through`() = runTest {
        val resolve = useCase(mapOf("hdr" to hdrOnly, "sdr" to playable(1080)))
        assertEquals(1080, resolve(listOf("hdr", "sdr")).height)
        assertEquals(listOf("hdr", "sdr"), attempted)
    }

    @Test
    fun `the first candidate that works wins without trying the rest`() = runTest {
        val resolve = useCase(mapOf("a" to playable(720), "b" to playable(2160)))
        assertEquals(720, resolve(listOf("a", "b")).height)
        assertEquals(listOf("a"), attempted)
    }

    @Test
    fun `when every candidate fails the last error is thrown with the earlier ones suppressed`() = runTest {
        val resolve = useCase(mapOf("k1" to unplayable("k1"), "k2" to hdrOnly, "k3" to unplayable("k3"), "k4" to playable(1080)))

        val error = assertThrows<IOException> { resolve(listOf("k1", "k2", "k3", "k4")) }

        assertEquals(listOf("k1", "k2", "k3"), attempted, "at most 3 candidates are tried")
        assertEquals(unplayable("k3").message, error.message)
        // Coroutine stack-trace recovery may rethrow a copy whose cause is the original exception.
        val suppressed = generateSequence<Throwable>(error) { it.cause }.flatMap { it.suppressed.asSequence() }.toList()
        assertEquals(listOf(unplayable("k1").message, NoTrailerStreamException("k2").message), suppressed.map { it.message })
        assertTrue(suppressed[1] is NoTrailerStreamException, "the HDR-only candidate counts as a failure")
    }

    @Test
    fun `no candidates throws without extracting anything`() = runTest {
        assertThrows<NoTrailerStreamException> { useCase(emptyMap())(emptyList()) }
        assertTrue(attempted.isEmpty())
    }

    @Test
    fun `cancelling during the first extraction stops the loop`() = runTest {
        val resolve = ResolveTrailerStreamUseCase(
            extractor = { key ->
                attempted += key
                awaitCancellation()
            },
            codecs = everything,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val job = launch { resolve(listOf("slow", "next")) }
        runCurrent()
        job.cancel()
        runCurrent()

        assertTrue(job.isCancelled)
        assertEquals(listOf("slow"), attempted, "cancellation is never treated as a failed candidate")
    }
}
