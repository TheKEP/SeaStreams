package com.seastreams.tv.player.audio

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Passthrough switchable during playback: the gate sink and the audio renderer reset (PRD §9). */
class PassthroughGateSinkTest {

    private val pcm = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setPcmEncoding(C.ENCODING_PCM_16BIT).build()
    private val encoded = listOf(MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_TRUEHD, MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD)
        .map { Format.Builder().setSampleMimeType(it).build() }
    private val delegate = mockk<AudioSink>(relaxed = true) {
        every { getFormatSupport(any()) } returns AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        every { supportsFormat(any()) } returns true
    }

    @Test
    fun `passthrough off reports every encoded format unsupported, so it gets decoded to PCM`() {
        val gate = PassthroughGateSink(delegate, allowPassthrough = false)
        encoded.forEach { format ->
            assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, gate.getFormatSupport(format), format.sampleMimeType)
            assertFalse(gate.supportsFormat(format))
        }
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, gate.getFormatSupport(pcm))
        assertTrue(gate.supportsFormat(pcm))
    }

    @Test
    fun `passthrough on defers to the real sink, and the gate can flip mid-playback`() {
        val gate = PassthroughGateSink(delegate, allowPassthrough = true)
        encoded.forEach { assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, gate.getFormatSupport(it)) }
        gate.allowPassthrough = false
        assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, gate.getFormatSupport(encoded.first()))
        gate.allowPassthrough = true
        assertTrue(gate.supportsFormat(encoded.first()))
        verify { delegate.getFormatSupport(encoded.first()) }
    }

    private class FakePlayer {
        val disabledCalls = mutableListOf<Boolean>()
        var audioSelected = true
        val scheduled = mutableListOf<Pair<Long, () -> Unit>>()

        val reset = AudioRendererReset(
            setAudioDisabled = { disabled ->
                disabledCalls += disabled
                if (disabled) audioSelected = false
            },
            audioSelected = { audioSelected },
            schedule = { delay, action -> scheduled += delay to action },
        )
    }

    @Test
    fun `the reset disables audio and re-enables it once ExoPlayer has dropped the track`() {
        val player = FakePlayer()
        player.reset.request()
        assertEquals(listOf(true), player.disabledCalls)
        assertTrue(player.reset.pending)

        player.reset.request() // a second toggle while pending changes nothing
        assertEquals(listOf(true), player.disabledCalls)

        player.reset.onTracksChanged()
        assertEquals(listOf(true, false), player.disabledCalls)
        assertFalse(player.reset.pending)

        player.scheduled.single().second() // the fallback after it already finished
        assertEquals(listOf(true, false), player.disabledCalls)
    }

    @Test
    fun `tracks still showing audio don't finish it early, the fallback does`() {
        val player = FakePlayer()
        player.reset.request()
        player.audioSelected = true // the reselection hasn't happened yet
        player.reset.onTracksChanged()
        assertEquals(listOf(true), player.disabledCalls)

        val (delay, fallback) = player.scheduled.single()
        assertEquals(AudioRendererReset.FALLBACK_MS, delay)
        fallback()
        assertEquals(listOf(true, false), player.disabledCalls)
    }
}
