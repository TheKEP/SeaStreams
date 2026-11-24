package com.seastreams.tv.player.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.seastreams.tv.core.datastore.PlaybackSpeed
import com.seastreams.tv.core.datastore.VolumeBoost
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/** Volume Boost gain stage and the speed-scaled Audio Delay in [DialogueNightAudioProcessor] (PRD §9). */
class VolumeBoostProcessorTest {

    private val rate = 48_000

    private fun processor(config: AudioDspConfig, channels: Int, encoding: Int = C.ENCODING_PCM_16BIT) =
        DialogueNightAudioProcessor(config).apply {
            configure(AudioProcessor.AudioFormat(rate, channels, encoding))
            flush()
        }

    private fun DialogueNightAudioProcessor.runShorts(input: ShortArray): ShortArray {
        val buffer = ByteBuffer.allocateDirect(input.size * 2).order(ByteOrder.nativeOrder())
        input.forEach { buffer.putShort(it) }
        buffer.flip()
        queueInput(buffer)
        val out = getOutput()
        return ShortArray(out.remaining() / 2) { out.getShort() }
    }

    private fun DialogueNightAudioProcessor.runFloats(input: FloatArray): FloatArray {
        val buffer = ByteBuffer.allocateDirect(input.size * 4).order(ByteOrder.nativeOrder())
        input.forEach { buffer.putFloat(it) }
        buffer.flip()
        queueInput(buffer)
        val out = getOutput()
        return FloatArray(out.remaining() / 4) { out.getFloat() }
    }

    private fun sine(frames: Int, channels: Int, amplitude: Double): DoubleArray =
        DoubleArray(frames * channels) { i -> sin(2 * PI * 440.0 * (i / channels) / rate) * amplitude }

    private fun DoubleArray.toShorts() = ShortArray(size) { (this[it] * Short.MAX_VALUE).roundToInt().toShort() }

    private fun boost(level: VolumeBoost) = AudioDspConfig(volumeGain = level.gain)

    @Test
    fun `plus 100 percent doubles a quiet 16-bit signal`() {
        val input = sine(4_800, 2, 0.2).toShorts()
        val output = processor(boost(VolumeBoost.PLUS_100), channels = 2).runShorts(input)
        val gain = output.maxOf { abs(it.toInt()) }.toDouble() / input.maxOf { abs(it.toInt()) }
        assertEquals(2.0, gain, 0.01)
    }

    @Test
    fun `every boost level scales a quiet float signal by its gain`() {
        val input = FloatArray(4_800) { (sin(2 * PI * 440.0 * it / rate) * 0.2).toFloat() }
        VolumeBoost.entries.forEach { level ->
            val output = processor(boost(level), channels = 1, encoding = C.ENCODING_PCM_FLOAT).runFloats(input)
            val gain = output.maxOf { abs(it) } / input.maxOf { abs(it) }
            assertEquals(level.gain, gain, 0.01f, level.name)
        }
    }

    @Test
    fun `boosted loud peaks are soft limited and never wrap`() {
        val input = sine(4_800, 2, 0.8).toShorts()
        val output = processor(boost(VolumeBoost.PLUS_100), channels = 2).runShorts(input)
        // No wrap-around: every boosted sample keeps the sign of its input.
        input.indices.filter { abs(input[it].toInt()) > 1_000 }.forEach { i ->
            assertEquals(input[i] > 0, output[i] > 0, "sample $i flipped sign")
        }
        // Soft, not folded: louder input never comes out quieter, and peaks bend towards full scale.
        val byInput = input.indices.sortedBy { input[it].toInt() }
        byInput.zipWithNext().forEach { (a, b) -> assertTrue(output[a] <= output[b], "non-monotonic at $a/$b") }
        val quietGain = output.indices.filter { abs(input[it].toInt()) in 2_000..8_000 }.map { output[it].toDouble() / input[it] }
        assertTrue(quietGain.all { abs(it - 2.0) < 0.01 }, "quiet samples get the full x2")
        val loudGain = output[input.indices.maxBy { input[it].toInt() }].toDouble() / input.maxOf { it.toInt() }
        assertTrue(loudGain < 1.3, "the 0.8 peak is limited, not doubled (gain $loudGain)")
    }

    @Test
    fun `Standard leaves PCM byte-identical`() {
        val input = sine(4_800, 2, 0.9).toShorts()
        val output = processor(boost(VolumeBoost.STANDARD), channels = 2).runShorts(input)
        assertArrayEquals(input, output)
    }

    @Test
    fun `boost can change mid-stream`() {
        val processor = processor(boost(VolumeBoost.STANDARD), channels = 1)
        val input = ShortArray(480) { 1_000 }
        assertEquals(1_000.toShort(), processor.runShorts(input)[0])
        processor.config = boost(VolumeBoost.PLUS_50)
        assertEquals(1_500.toShort(), processor.runShorts(input)[0])
    }

    @Test
    fun `audio delay is scaled by speed so it stays constant in wall-clock time`() {
        val input = ShortArray(48_000 * 2) { 1_000 }
        val config = AudioDspConfig(delayMs = 100, playbackSpeed = PlaybackSpeed.PLUS_100.multiplier)
        val output = processor(config, channels = 2).runShorts(input)
        // 100 ms at 2x = 200 ms of media-time silence, which Sonic plays out in 100 ms.
        val delayFrames = rate / 5
        assertEquals(input.size + delayFrames * 2, output.size)
        assertTrue((0 until delayFrames * 2).all { output[it] == 0.toShort() })
        assertEquals(1_000.toShort(), output[delayFrames * 2])
    }

    @Test
    fun `slower speed shortens the media-time delay`() {
        val input = ShortArray(48_000) { 1_000 }
        val config = AudioDspConfig(delayMs = 100, playbackSpeed = PlaybackSpeed.MINUS_25.multiplier)
        val output = processor(config, channels = 1).runShorts(input)
        assertEquals(input.size + 3_600, output.size) // 100 ms × 0.75 = 75 ms = 3 600 frames
    }
}
