package com.seastreams.tv.domain.usecase

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ResolveTrailerStreamUseCaseTest {

    private val everything = VideoCodecSupport { _, _, _ -> true }

    private fun video(height: Int, fps: Int = 30, codec: String = "avc1.640028", container: TrailerContainer = TrailerContainer.MP4, videoOnly: Boolean = true, bitrate: Int = height * 1_000) =
        TrailerVideoCandidate("https://v/$height-$fps-$codec-$container-$videoOnly", height, fps, bitrate, codec, container, videoOnly, dashManifest = "<MPD $height/>")

    private fun audio(bitrate: Int, container: TrailerContainer = TrailerContainer.M4A) =
        TrailerAudioCandidate("https://a/$bitrate-$container", bitrate, container, dashManifest = "<MPD audio/>")

    @Test
    fun `picks the tallest adaptive stream up to 2160p with the best M4A audio`() {
        val extracted = ExtractedTrailer(
            videos = listOf(video(1080), video(2160, codec = "vp09.00.51.08", container = TrailerContainer.WEBM), video(4320), video(720)),
            audios = listOf(audio(128_000), audio(160_000, TrailerContainer.WEBMA), audio(48_000)),
        )
        val stream = TrailerStreamSelector.select(extracted, everything)

        assertEquals(2160, stream?.height)
        assertEquals("https://a/128000-M4A", stream?.audio?.url, "WebM/Opus audio is skipped: only M4A pairs")
        assertEquals("<MPD 2160/>", stream?.video?.dashManifest)
    }

    @Test
    fun `ties prefer higher frame rate, then MP4, then bitrate`() {
        val extracted = ExtractedTrailer(
            videos = listOf(
                video(1080, fps = 30),
                video(1080, fps = 60, container = TrailerContainer.WEBM, codec = "vp9"),
                video(1080, fps = 60, container = TrailerContainer.MP4),
            ),
            audios = listOf(audio(128_000)),
        )
        val stream = TrailerStreamSelector.select(extracted, everything)
        assertEquals("video/mp4", stream?.video?.mimeType)
        assertEquals("https://v/1080-60-avc1.640028-MP4-true", stream?.video?.url)
    }

    @Test
    fun `codecs the device can't decode are skipped`() {
        val noAv1 = VideoCodecSupport { codec, _, _ -> videoCodecFamily(codec) != "av1" }
        val extracted = ExtractedTrailer(listOf(video(2160, codec = "av01.0.12M.08"), video(1440, codec = "vp09.00.50.08")), listOf(audio(128_000)))
        assertEquals(1440, TrailerStreamSelector.select(extracted, noAv1)?.height)
    }

    @Test
    fun `sizes and frame rates the decoder can't handle are skipped`() {
        val upTo4k30 = VideoCodecSupport { _, height, fps -> height < 2160 || fps <= 30 }
        val extracted = ExtractedTrailer(listOf(video(2160, fps = 60), video(2160, fps = 30), video(1440, fps = 60)), listOf(audio(128_000)))
        val stream = TrailerStreamSelector.select(extracted, upTo4k30)
        assertEquals("https://v/2160-30-avc1.640028-MP4-true", stream?.video?.url)
    }

    @Test
    fun `without an adaptive pair the best muxed stream is used`() {
        val extracted = ExtractedTrailer(
            videos = listOf(video(360, videoOnly = false), video(720, videoOnly = false), video(1080)),
            audios = emptyList(),
        )
        val stream = TrailerStreamSelector.select(extracted, everything)
        assertEquals(720, stream?.height)
        assertNull(stream?.audio)
    }

    @Test
    fun `inline previews can cap the height`() {
        val extracted = ExtractedTrailer(listOf(video(2160), video(1080), video(720)), listOf(audio(128_000)))
        assertEquals(720, TrailerStreamSelector.select(extracted, everything, maxHeight = 720)?.height)
    }

    @Test
    fun `nothing playable gives null`() {
        assertNull(TrailerStreamSelector.select(ExtractedTrailer(emptyList(), listOf(audio(1))), everything))
    }

    @Test
    fun `the use case extracts off the caller's thread and returns the selection`() = runTest {
        val extractor = TrailerExtractor { key ->
            assertEquals("abc123", key)
            ExtractedTrailer(listOf(video(1080)), listOf(audio(128_000)))
        }
        val useCase = ResolveTrailerStreamUseCase(extractor, everything, StandardTestDispatcher(testScheduler))
        assertEquals(1080, useCase("abc123").height)
    }

    @Test
    fun `extraction errors and empty results throw so callers can fall back`() = runTest {
        val failing = ResolveTrailerStreamUseCase({ throw IOException("blocked") }, everything, StandardTestDispatcher(testScheduler))
        assertThrows<IOException> { failing("x") }

        val empty = ResolveTrailerStreamUseCase({ ExtractedTrailer(emptyList(), emptyList()) }, everything, StandardTestDispatcher(testScheduler))
        assertThrows<NoTrailerStreamException> { empty("x") }
    }

    @Test
    fun `codec strings map to families`() {
        assertEquals("avc", videoCodecFamily("avc1.4d401f"))
        assertEquals("vp9", videoCodecFamily("vp09.00.40.08"))
        assertEquals("av1", videoCodecFamily("av01.0.08M.08"))
        assertNull(videoCodecFamily(null))
    }
}
