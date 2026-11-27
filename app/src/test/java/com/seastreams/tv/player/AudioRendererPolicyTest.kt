package com.seastreams.tv.player

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import com.seastreams.tv.player.audio.DialogueNightAudioProcessor
import com.seastreams.tv.player.audio.SafeDownmixProcessor
import com.seastreams.tv.player.audio.SpeechActivityTap
import com.seastreams.tv.player.subtitle.sync.SpeechEnergyLog
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** FFmpeg software-decoding fallback and the PCM processor chain (ARCHITECTURE.md §5). */
class AudioRendererPolicyTest {

    private val mediaCodec = mockk<Renderer>()
    private val ffmpeg = mockk<Renderer>()

    @Test
    fun `extension renderers are on, after MediaCodec, so passthrough and hardware decoding win`() {
        assertEquals(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON, AudioRendererPolicy.EXTENSION_MODE)
        val out = mutableListOf<Renderer>(mockk(), mediaCodec)
        assertTrue(AudioRendererPolicy.addSoftwareRenderer(out, firstAudioIndex = 1, AudioRendererPolicy.EXTENSION_MODE) { ffmpeg })
        assertSame(mediaCodec, out[1])
        assertSame(ffmpeg, out[2])
    }

    @Test
    fun `prefer mode puts FFmpeg first and off mode adds nothing`() {
        val preferred = mutableListOf<Renderer>(mockk(), mediaCodec)
        AudioRendererPolicy.addSoftwareRenderer(preferred, 1, DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER) { ffmpeg }
        assertSame(ffmpeg, preferred[1])

        val off = mutableListOf(mediaCodec)
        assertFalse(AudioRendererPolicy.addSoftwareRenderer(off, 0, DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF) { ffmpeg })
        assertEquals(listOf(mediaCodec), off)
    }

    @Test
    fun `a missing native library keeps the MediaCodec renderers only`() {
        val out = mutableListOf(mediaCodec)
        assertFalse(AudioRendererPolicy.addSoftwareRenderer(out, 0, AudioRendererPolicy.EXTENSION_MODE) { throw UnsatisfiedLinkError("no libmedia3ext") })
        assertEquals(listOf(mediaCodec), out)
    }

    @Test
    fun `the NextLib FFmpeg audio renderer is on the classpath`() {
        val renderer = Class.forName("io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegAudioRenderer")
        assertTrue(Renderer::class.java.isAssignableFrom(renderer))
    }

    @Test
    fun `processor chain is tap, then downmix when needed, then DSP`() {
        val tap = SpeechActivityTap(SpeechEnergyLog())
        val downmix = SafeDownmixProcessor()
        val dsp = DialogueNightAudioProcessor()
        assertEquals(listOf<AudioProcessor>(tap, downmix, dsp), AudioRendererPolicy.processorChain(tap, downmix, dsp).toList())
        assertEquals(listOf<AudioProcessor>(tap, dsp), AudioRendererPolicy.processorChain(tap, null, dsp).toList())
    }

    @Test
    fun `downmix only for outputs that take at most two PCM channels`() {
        assertTrue(AudioRendererPolicy.downmixToStereo(2))
        assertTrue(AudioRendererPolicy.downmixToStereo(1))
        assertFalse(AudioRendererPolicy.downmixToStereo(6))
        assertFalse(AudioRendererPolicy.downmixToStereo(8))
        assertFalse(AudioRendererPolicy.downmixToStereo(0), "unknown capability: leave it to the platform")
    }
}
