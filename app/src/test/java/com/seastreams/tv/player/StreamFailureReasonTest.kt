package com.seastreams.tv.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlaybackException
import com.seastreams.tv.data.debrid.api.DebridAuthException
import com.seastreams.tv.data.debrid.stream.DebridSwitchedOffException
import com.seastreams.tv.usenet.archive.ArchiveRefusedException
import com.seastreams.tv.usenet.archive.UsenetUnplayableException
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** PRD §9/§12/§14: brief, secret-free failure reasons for the failover status, the stopped pill and the final toast. */
class StreamFailureReasonTest {

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "7z archives can't be streamed|7z archive not supported",
            "Zip archives can't be streamed|zip archive not supported",
            "The archive is password-protected|password-protected archive",
            "The archive is compressed (only stored archives can stream)|compressed RAR not supported",
            "12 articles are missing from the release|missing articles",
            "1 article is missing, too many to repair|missing articles",
            "The start of a file is missing|missing articles",
            "A volume of the archive is missing|archive volume missing",
            "Volume 3 of the archive is damaged|damaged archive",
            "The release has no video file|no video in release",
            "The release isn't a video file|no video in release",
            "This Usenet result has expired. Look up the streams again.|result expired",
            "Add a Usenet provider in Settings > Sources|no Usenet provider set up",
            "Usenet is switched off in Settings|Usenet is off",
            "That Debrid file isn't listed any more|file not found on Debrid",
            "Debrid is switched off in Settings|Debrid is off",
            "Invalid API key or token|API key or sign-in rejected",
            "Account isn't premium|Debrid account not premium",
            "HTTP 503|HTTP 503",
            "Read timed out|connection timed out",
        ],
    )
    fun `preflight messages map to a brief reason`(message: String, expected: String) {
        assertEquals(expected, StreamFailureReason.fromPreflight(message))
    }

    @Test
    fun `an unknown preflight message is passed through sanitized and brief`() {
        assertEquals("the indexer said no", StreamFailureReason.fromPreflight("The indexer said no."))
        assertEquals(StreamFailureReason.GENERIC, StreamFailureReason.fromPreflight("   "))
    }

    @Test
    fun `sanitize hides URLs, credentials and tokens`() {
        val raw = "Failed https://dl.real-debrid.com/d/ABCDEF123456/Movie.mkv?token=s3cr3t apikey=abc123 for user=me@example.com " +
            "hash 0123456789abcdef0123456789abcdef"
        val clean = StreamFailureReason.sanitize(raw)
        listOf("real-debrid", "ABCDEF123456", "s3cr3t", "abc123", "me@example.com", "0123456789abcdef").forEach {
            assertFalse(clean.contains(it), "\"$it\" leaked into \"$clean\"")
        }
        assertTrue(clean.length <= 60, clean)
        assertTrue(clean.startsWith("failed"), clean)
    }

    @Test
    fun `sanitize caps long text and keeps acronyms`() {
        val long = StreamFailureReason.sanitize("NNTP " + "word ".repeat(40))
        assertTrue(long.endsWith("…") && long.length == 60, long)
        assertTrue(long.startsWith("NNTP"), long)
    }

    @Test
    fun `a word inside a URL never picks the reason`() {
        val error = PlaybackException("load", IOException("unexpected end of stream on https://host/Movie.7z.mkv?password=x"), PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        assertEquals(StreamFailureReason.GENERIC, StreamFailureReason.fromPlayback(error))
    }

    @Test
    fun `playback errors carry the archive, Usenet or Debrid cause`() {
        fun wrapped(cause: Throwable) = PlaybackException("source", cause, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        assertEquals("password-protected archive", StreamFailureReason.fromPlayback(wrapped(ArchiveRefusedException("The archive is password-protected"))))
        assertEquals("missing articles", StreamFailureReason.fromPlayback(wrapped(UsenetUnplayableException("3 articles are missing from the release"))))
        assertEquals("Debrid is off", StreamFailureReason.fromPlayback(wrapped(IOException(DebridSwitchedOffException()))))
        assertEquals("API key or sign-in rejected", StreamFailureReason.fromThrowable(DebridAuthException()))
    }

    @Test
    fun `HTTP status, timeouts and unknown hosts`() {
        val dataSpec = mockk<DataSpec>(relaxed = true)
        val forbidden = HttpDataSource.InvalidResponseCodeException(403, "Forbidden", null, emptyMap(), dataSpec, ByteArray(0))
        val bad = PlaybackException("http", forbidden, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        assertEquals("HTTP 403 (access denied)", StreamFailureReason.fromPlayback(bad))
        assertEquals("HTTP error", StreamFailureReason.fromPlayback(PlaybackException("http", null, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)))
        assertEquals("connection timed out", StreamFailureReason.fromPlayback(PlaybackException("t", SocketTimeoutException(), PlaybackException.ERROR_CODE_IO_UNSPECIFIED)))
        assertEquals("connection timed out", StreamFailureReason.fromPlayback(PlaybackException("t", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)))
        assertEquals("server not found", StreamFailureReason.fromThrowable(IOException(UnknownHostException("cdn.example"))))
    }

    @Test
    fun `decoder errors name the track kind and codec`() {
        val hevc = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).build()
        val video = ExoPlaybackException.createForRenderer(
            IllegalStateException("decoder"), "MediaCodecVideoRenderer", 0, hevc, C.FORMAT_EXCEEDS_CAPABILITIES, false,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        )
        assertEquals("unsupported video codec (HEVC)", StreamFailureReason.fromPlayback(video))
        val dts = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_DTS).build()
        val audio = ExoPlaybackException.createForRenderer(
            IllegalStateException("decoder"), "MediaCodecAudioRenderer", 1, dts, C.FORMAT_UNSUPPORTED_SUBTYPE, false,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        )
        assertTrue(StreamFailureReason.fromPlayback(audio).startsWith("unsupported audio codec"))
        assertEquals("unsupported container", StreamFailureReason.fromPlayback(PlaybackException("p", null, PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED)))
        assertEquals("DRM-protected stream", StreamFailureReason.fromPlayback(PlaybackException("d", null, PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED)))
    }

    // ---- Wording --------------------------------------------------------------------------------

    @Test
    fun `status texts carry the reason, or read as before without one`() {
        assertEquals(
            "Stream 3 didn't start (password-protected archive). Trying stream 4 of 12…",
            StreamFailureMessages.skipped(2, 3, 12, "password-protected archive"),
        )
        assertEquals("Stream 1 failed (HTTP 403 (access denied)). Trying stream 2 of 3…", StreamFailureMessages.advancing(0, 1, 3, FallbackReason.ERROR, "HTTP 403 (access denied)"))
        assertEquals("Stream 1 didn't start. Trying stream 2 of 3…", StreamFailureMessages.advancing(0, 1, 3, FallbackReason.TIMEOUT, null))
        assertEquals("None of the streams would play (last: 7z archive not supported)", StreamFailureMessages.exhausted(FallbackReason.ERROR, "7z archive not supported"))
        assertEquals("None of the streams started in time", StreamFailureMessages.exhausted(FallbackReason.TIMEOUT, null))
        assertEquals("None of the streams would play", StreamFailureMessages.exhausted(FallbackReason.ERROR, null))
        assertEquals(
            "Stream 3 of 12 couldn't play (password-protected archive). Automatic failover is off — pick another stream.",
            StreamFailureMessages.stopped(2, 12, "password-protected archive"),
        )
    }
}
