package com.seastreams.tv.player.audio

import androidx.media3.common.C
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * Sample access shared by the PCM audio processors ([SpeechActivityTap], [SafeDownmixProcessor],
 * [DialogueNightAudioProcessor]): 16-bit and float PCM in native byte order, as normalised floats.
 */
internal object PcmSamples {
    private const val SHORT_SCALE = 32_768f
    private const val SHORT_BYTES = 2
    private const val FLOAT_BYTES = 4

    /** The encodings the processors handle; anything else leaves them inactive. */
    fun isSupported(encoding: Int): Boolean = encoding == C.ENCODING_PCM_16BIT || encoding == C.ENCODING_PCM_FLOAT

    fun bytesPerSample(encoding: Int): Int = if (encoding == C.ENCODING_PCM_FLOAT) FLOAT_BYTES else SHORT_BYTES

    /** Reads the next sample (relative read). */
    fun read(buffer: ByteBuffer, encoding: Int): Float =
        if (encoding == C.ENCODING_PCM_FLOAT) buffer.getFloat() else buffer.getShort() / SHORT_SCALE

    /** Reads the sample at byte [index] (absolute read; the position is unchanged). */
    fun read(buffer: ByteBuffer, index: Int, encoding: Int): Float =
        if (encoding == C.ENCODING_PCM_FLOAT) buffer.getFloat(index) else buffer.getShort(index) / SHORT_SCALE

    /** Writes [value] clamped to ±1.0; 16-bit output saturates, never wraps. */
    fun write(buffer: ByteBuffer, encoding: Int, value: Float) {
        val clamped = value.coerceIn(-1f, 1f)
        if (encoding == C.ENCODING_PCM_FLOAT) {
            buffer.putFloat(clamped)
        } else {
            buffer.putShort((clamped * SHORT_SCALE).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
        }
    }
}
