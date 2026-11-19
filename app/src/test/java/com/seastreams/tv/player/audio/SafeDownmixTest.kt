package com.seastreams.tv.player.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class SafeDownmixTest {

    private fun processor(channels: Int, encoding: Int = C.ENCODING_PCM_16BIT) = SafeDownmixProcessor().apply {
        configure(AudioProcessor.AudioFormat(48_000, channels, encoding))
        flush()
    }

    @Test
    fun `every row sums to at most 1 for 3 to 8 channels`() {
        (3..8).forEach { channels ->
            val matrix = requireNotNull(SafeDownmixProcessor.stereoMatrix(channels))
            matrix.forEach { row -> assertTrue(row.sumOf { abs(it).toDouble() } <= 1.0 + 1e-6, "$channels ch") }
            assertEquals(matrix[0].sum(), matrix[1].sum(), 1e-6f, "$channels ch is balanced")
        }
    }

    @Test
    fun `5_1 maps to Android channel order and drops LFE`() {
        val (left, right) = requireNotNull(SafeDownmixProcessor.stereoMatrix(6)).let { it[0] to it[1] }
        assertTrue(left[0] > 0 && right[0] == 0f, "FL left only")
        assertTrue(right[1] > 0 && left[1] == 0f, "FR right only")
        assertEquals(left[2], right[2], "FC both sides")
        assertEquals(0f, left[3])
        assertTrue(left[4] > 0 && right[4] == 0f, "BL left")
        assertTrue(right[5] > 0 && left[5] == 0f, "BR right")
    }

    @Test
    fun `full scale on every channel at once never clips`() {
        listOf(6, 8).forEach { channels ->
            val frames = 256
            val input = ByteBuffer.allocateDirect(frames * channels * 2).order(ByteOrder.nativeOrder())
            repeat(frames * channels) { input.putShort(Short.MAX_VALUE) }
            input.flip()
            val downmix = processor(channels)
            downmix.queueInput(input)
            val out = downmix.getOutput()
            assertEquals(frames * 2 * 2, out.remaining(), "stereo out")
            while (out.hasRemaining()) {
                val sample = out.getShort().toInt()
                assertTrue(sample in 0..Short.MAX_VALUE, "$channels ch: $sample")
            }
        }
    }

    @Test
    fun `half scale everywhere comes out at half scale, so the clamp is never what saves it`() {
        val input = ByteBuffer.allocateDirect(6 * 2).order(ByteOrder.nativeOrder())
        repeat(6) { input.putShort(16_384) }
        input.flip()
        val downmix = processor(6)
        downmix.queueInput(input)
        val out = downmix.getOutput()
        repeat(2) { assertTrue(abs(out.getShort() - 16_384) <= 1) }
    }

    @Test
    fun `float PCM is downmixed within plus or minus 1`() {
        val input = ByteBuffer.allocateDirect(6 * 4).order(ByteOrder.nativeOrder())
        repeat(6) { input.putFloat(-1f) }
        input.flip()
        val downmix = processor(6, C.ENCODING_PCM_FLOAT)
        downmix.queueInput(input)
        val out = downmix.getOutput()
        repeat(2) { assertTrue(out.getFloat() >= -1f) }
    }

    @Test
    fun `mono, stereo and bitstream input leave it inactive`() {
        assertNull(SafeDownmixProcessor.stereoMatrix(2))
        assertNull(SafeDownmixProcessor.stereoMatrix(9))
        assertFalse(processor(2).isActive)
        assertFalse(processor(1).isActive)
        assertFalse(processor(6, C.ENCODING_AC3).isActive)
        assertTrue(processor(6).isActive)
    }
}
