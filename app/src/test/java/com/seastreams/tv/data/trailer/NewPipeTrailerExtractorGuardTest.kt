package com.seastreams.tv.data.trailer

import com.seastreams.tv.domain.usecase.TrailerExtractorUnavailableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.IOException

/**
 * PRD §9 *Runtime compatibility*: NewPipe failures — including `LinkageError`s such as Fire OS's missing
 * API 33 `URLDecoder.decode(String, Charset)` — surface as `IOException`s, never as a crash.
 */
class NewPipeTrailerExtractorGuardTest {

    private fun extractor(initialize: () -> Unit = {}, fetchInfo: (String) -> StreamInfo) =
        NewPipeTrailerExtractor(initialize, fetchInfo)

    private suspend fun failureOf(extractor: NewPipeTrailerExtractor): Throwable =
        try {
            extractor.extract("abc123")
            throw AssertionError("extraction was expected to fail")
        } catch (e: AssertionError) {
            throw e
        } catch (e: Throwable) {
            e
        }

    @Test
    fun `NoSuchMethodError from extraction becomes TrailerExtractorUnavailableException`() = runTest {
        val error = NoSuchMethodError("No static method decode(Ljava/lang/String;Ljava/nio/charset/Charset;)")
        val failure = failureOf(extractor { throw error })

        assertTrue(failure is TrailerExtractorUnavailableException, "got $failure")
        assertTrue(failure is IOException)
        assertSame(error, failure.cause)
    }

    @Test
    fun `ExceptionInInitializerError during NewPipe init is caught too`() = runTest {
        val failure = failureOf(
            extractor(initialize = { throw ExceptionInInitializerError("static init") }) { throw AssertionError("not reached") },
        )
        assertTrue(failure is TrailerExtractorUnavailableException, "got $failure")
        assertTrue(failure.cause is ExceptionInInitializerError)
    }

    @Test
    fun `NoClassDefFoundError is caught too`() = runTest {
        val failure = failureOf(extractor { throw NoClassDefFoundError("java/net/Missing") })
        assertTrue(failure is TrailerExtractorUnavailableException, "got $failure")
    }

    @Test
    fun `an ordinary extraction failure stays a plain IOException`() = runTest {
        val failure = failureOf(extractor { throw ParsingException("UNPLAYABLE") })

        assertTrue(failure is IOException, "got $failure")
        assertFalse(failure is TrailerExtractorUnavailableException, "only runtime gaps skip the other candidates")
        // Coroutine stack-trace recovery may wrap a copy around the original, so search the cause chain.
        assertTrue(generateSequence(failure.cause) { it.cause }.any { it is ParsingException })
    }

    @Test
    fun `the watch URL carries the YouTube key`() = runTest {
        var url = ""
        failureOf(extractor { requested -> url = requested; throw ParsingException("stop") })
        assertEquals("https://www.youtube.com/watch?v=abc123", url)
    }

    @Test
    fun `cancellation propagates unwrapped`() = runTest {
        val cancel = CancellationException("hover moved on")
        val failure = failureOf(extractor { throw cancel })
        assertTrue(failure is CancellationException, "got $failure")
        assertFalse(failure is IOException)
    }
}
