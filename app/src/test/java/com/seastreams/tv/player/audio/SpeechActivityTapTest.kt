package com.seastreams.tv.player.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.seastreams.tv.player.subtitle.sync.SpeechEnergyLog
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

class SpeechActivityTapTest {

    private val rate = 48_000

    private fun tap(log: SpeechEnergyLog, channels: Int, positionOffsetUs: Long, encoding: Int = C.ENCODING_PCM_16BIT) =
        SpeechActivityTap(log).apply {
            collecting = true
            configure(AudioProcessor.AudioFormat(rate, channels, encoding))
            flush(AudioProcessor.StreamMetadata(positionOffsetUs))
        }

    /** [frames] of a [hz] sine at half scale on [onChannel] (every channel when `null`). */
    private fun sine(frames: Int, channels: Int, hz: Double, onChannel: Int? = null): ShortArray = ShortArray(frames * channels) { i ->
        val channel = i % channels
        if (onChannel == null || channel == onChannel) (sin(2 * PI * hz * (i / channels) / rate) * 0.5 * Short.MAX_VALUE).roundToInt().toShort() else 0
    }

    private fun SpeechActivityTap.run(samples: ShortArray): ShortArray {
        val buffer = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder())
        samples.forEach { buffer.putShort(it) }
        buffer.flip()
        queueInput(buffer)
        val out = getOutput()
        return ShortArray(out.remaining() / 2) { out.getShort() }
    }

    @Test
    fun `audio passes through byte for byte`() {
        val input = sine(9_600, 2, 440.0)
        assertArrayEquals(input, tap(SpeechEnergyLog(), 2, 0).run(input))
    }

    @Test
    fun `cells are stamped with media time from the flush position onwards`() {
        val log = SpeechEnergyLog()
        // Flushed at 12.30 s; half a second of voice-band tone fills cells 123..127.
        tap(log, 2, 12_300_000).run(sine(24_000, 2, 1_000.0))

        val snapshot = log.snapshot(120, 130)
        assertEquals((0 until 10).map { it + 120 in 123..127 }, snapshot.covered.toList())
        assertTrue(snapshot.voiceRatio[3] > 0.5f, "a 1 kHz tone is voice band")
        assertEquals(127L, log.latestCell())
    }

    @Test
    fun `a flush mid-cell starts on the next cell boundary`() {
        val log = SpeechEnergyLog()
        // 12.35 s: the first 50 ms complete cell 123 only half, which still counts; then 124.
        tap(log, 1, 12_350_000).run(sine(7_200, 1, 1_000.0)) // 150 ms
        val snapshot = log.snapshot(122, 126)
        assertEquals(listOf(false, true, true, false), snapshot.covered.toList())
    }

    @Test
    fun `multichannel audio is measured on the centre channel`() {
        val centre = SpeechEnergyLog().also { tap(it, 6, 0).run(sine(4_800, 6, 1_000.0, onChannel = 2)) }
        val surroundsOnly = SpeechEnergyLog().also { tap(it, 6, 0).run(sine(4_800, 6, 1_000.0, onChannel = 4)) }
        assertTrue(centre.snapshot(0, 1).bandDb[0] > surroundsOnly.snapshot(0, 1).bandDb[0] + 60)
    }

    @Test
    fun `nothing is logged while not collecting, but the clock keeps running`() {
        val log = SpeechEnergyLog()
        val tap = tap(log, 1, 0).apply { collecting = false }
        tap.run(sine(4_800, 1, 1_000.0)) // cell 0, not logged
        assertNull(log.latestCell())
        tap.collecting = true
        tap.run(sine(4_800, 1, 1_000.0)) // cell 1
        assertEquals(1L, log.latestCell())
    }

    @Test
    fun `non-PCM encodings leave the tap inactive`() {
        val tap = SpeechActivityTap(SpeechEnergyLog())
        tap.configure(AudioProcessor.AudioFormat(rate, 6, C.ENCODING_AC3))
        assertFalse(tap.isActive)
    }
}
