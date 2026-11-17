package com.seastreams.tv.player.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

class DialogueNightAudioProcessorTest {

    private val rate = 48_000

    private fun processor(config: AudioDspConfig, channels: Int, encoding: Int = C.ENCODING_PCM_16BIT): DialogueNightAudioProcessor {
        val processor = DialogueNightAudioProcessor(config)
        processor.configure(AudioProcessor.AudioFormat(rate, channels, encoding))
        processor.flush()
        return processor
    }

    private fun shorts(samples: ShortArray): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder())
        samples.forEach { buffer.putShort(it) }
        buffer.flip()
        return buffer
    }

    private fun DialogueNightAudioProcessor.run(input: ShortArray): ShortArray {
        queueInput(shorts(input))
        val out = getOutput()
        return ShortArray(out.remaining() / 2) { out.getShort() }
    }

    /** [frames] frames of a sine at [hz] / [amplitude] on every channel listed in [onChannels]. */
    private fun sine(frames: Int, channels: Int, hz: Double, amplitude: Double, onChannels: Set<Int>): ShortArray =
        ShortArray(frames * channels) { i ->
            val frame = i / channels
            val channel = i % channels
            if (channel in onChannels) (sin(2 * PI * hz * frame / rate) * amplitude * Short.MAX_VALUE).roundToInt().toShort() else 0
        }

    private fun peak(samples: ShortArray, channels: Int, channel: Int, fromFrame: Int = 0): Int =
        (fromFrame until samples.size / channels).maxOf { abs(samples[it * channels + channel].toInt()) }

    @Test
    fun `with every effect off PCM passes through untouched`() {
        val input = sine(4_800, 2, 440.0, 0.5, setOf(0, 1))
        val output = processor(AudioDspConfig(), channels = 2).run(input)
        assertArrayEquals(input, output)
    }

    @Test
    fun `unsupported encodings leave the processor inactive`() {
        val processor = DialogueNightAudioProcessor(AudioDspConfig(dialogueBoostDb = 6))
        val format = processor.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_24BIT))
        assertFalse(processor.isActive)
        assertEquals(AudioProcessor.AudioFormat.NOT_SET, format)
    }

    @Test
    fun `5_1 dialogue boost raises only the centre channel`() {
        val channels = 6
        val input = sine(4_800, channels, 440.0, 0.1, setOf(0, 1, 2, 4, 5))
        val output = processor(AudioDspConfig(dialogueBoostDb = 6), channels).run(input)

        val centreGain = peak(output, channels, 2).toDouble() / peak(input, channels, 2)
        assertEquals(2.0, centreGain, 0.05, "+6 dB is about x2 on the centre channel")
        assertEquals(peak(input, channels, 0), peak(output, channels, 0), "front left untouched")
        assertEquals(peak(input, channels, 5), peak(output, channels, 5), "surround untouched")
    }

    @Test
    fun `stereo dialogue boost lifts the vocal band but not deep bass`() {
        val boosted = processor(AudioDspConfig(dialogueBoostDb = 12), channels = 2)
        val voice = sine(9_600, 2, 1_000.0, 0.05, setOf(0, 1))
        val voiceGain = peak(boosted.run(voice), 2, 0, fromFrame = 4_800).toDouble() / peak(voice, 2, 0)

        val bassProcessor = processor(AudioDspConfig(dialogueBoostDb = 12), channels = 2)
        val bass = sine(9_600, 2, 40.0, 0.05, setOf(0, 1))
        val bassGain = peak(bassProcessor.run(bass), 2, 0, fromFrame = 4_800).toDouble() / peak(bass, 2, 0)

        assertTrue(voiceGain > 3.0, "1 kHz should gain several times over, was $voiceGain")
        assertTrue(bassGain < 1.5, "40 Hz should stay near unity, was $bassGain")
    }

    @Test
    fun `boosted peaks are soft limited instead of wrapping around`() {
        val input = sine(4_800, 6, 440.0, 0.9, setOf(2))
        val output = processor(AudioDspConfig(dialogueBoostDb = 12), channels = 6).run(input)
        // Wrap-around would flip a sample's sign; limiting keeps every centre sample on its input's side.
        val centre = (0 until 4_800).map { input[it * 6 + 2] to output[it * 6 + 2] }
        assertTrue(centre.all { (inSample, outSample) -> inSample.toInt() * outSample.toInt() >= 0 })
        assertTrue(peak(output, 6, 2) > peak(input, 6, 2) * 0.95, "still at least as loud")
    }

    @Test
    fun `night mode narrows the gap between loud and quiet passages`() {
        val loudIn = sine(24_000, 2, 300.0, 0.9, setOf(0, 1))
        val quietIn = sine(24_000, 2, 300.0, 0.02, setOf(0, 1))
        val loudOut = processor(AudioDspConfig(nightMode = true), 2).run(loudIn)
        val quietOut = processor(AudioDspConfig(nightMode = true), 2).run(quietIn)

        val ratioIn = peak(loudIn, 2, 0).toDouble() / peak(quietIn, 2, 0)
        val ratioOut = peak(loudOut, 2, 0, fromFrame = 12_000).toDouble() / peak(quietOut, 2, 0, fromFrame = 12_000)
        assertTrue(ratioOut < ratioIn / 3, "dynamic range should shrink a lot: in=$ratioIn out=$ratioOut")
        assertTrue(peak(quietOut, 2, 0, fromFrame = 12_000) > peak(quietIn, 2, 0), "quiet speech comes up")
    }

    @Test
    fun `a positive delay inserts that much silence first`() {
        val input = ShortArray(2 * 1_000) { 1_000 }
        val output = processor(AudioDspConfig(delayMs = 100), channels = 2).run(input)

        val delayFrames = rate / 10
        assertEquals(input.size + delayFrames * 2, output.size)
        assertTrue((0 until delayFrames * 2).all { output[it] == 0.toShort() })
        assertEquals(1_000.toShort(), output[delayFrames * 2])
    }

    @Test
    fun `a negative delay drops that much audio so it plays earlier`() {
        val frames = 10_000
        val input = ShortArray(frames * 2) { (it / 2).toShort() }
        val output = processor(AudioDspConfig(delayMs = -25), channels = 2).run(input)

        val skipped = rate * 25 / 1_000
        assertEquals((frames - skipped) * 2, output.size)
        assertEquals(skipped.toShort(), output[0])
    }

    @Test
    fun `changing the delay mid-stream applies only the difference`() {
        val processor = processor(AudioDspConfig(delayMs = 50), channels = 1)
        val first = processor.run(ShortArray(100) { 7 })
        assertEquals(100 + rate / 20, first.size)

        processor.config = AudioDspConfig(delayMs = 75)
        val second = processor.run(ShortArray(100) { 7 })
        assertEquals(100 + rate * 25 / 1_000, second.size)

        processor.config = AudioDspConfig(delayMs = 0)
        val third = processor.run(ShortArray(rate / 10) { 7 })
        assertEquals(rate / 10 - rate * 75 / 1_000, third.size)
    }

    @Test
    fun `a flush (seek) re-applies the configured delay from scratch`() {
        val processor = processor(AudioDspConfig(delayMs = 100), channels = 1)
        processor.run(ShortArray(10))
        processor.flush()
        val afterSeek = processor.run(ShortArray(10) { 3 })
        assertEquals(10 + rate / 10, afterSeek.size)
    }

    @Test
    fun `float PCM is processed too`() {
        val processor = processor(AudioDspConfig(dialogueBoostDb = 6), channels = 6, encoding = C.ENCODING_PCM_FLOAT)
        val input = ByteBuffer.allocateDirect(6 * 4).order(ByteOrder.nativeOrder())
        repeat(6) { input.putFloat(0.1f) }
        input.flip()
        processor.queueInput(input)
        val out = processor.getOutput()
        val samples = FloatArray(6) { out.getFloat() }
        assertEquals(0.1f, samples[0], 1e-4f)
        assertEquals(0.2f, samples[DialogueNightAudioProcessor.CENTER_INDEX], 0.01f)
    }
}
