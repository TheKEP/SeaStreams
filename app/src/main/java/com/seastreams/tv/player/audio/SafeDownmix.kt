package com.seastreams.tv.player.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Clip-safe multichannel → stereo downmix, installed only when the connected output takes no more
 * than two PCM channels. Uses ITU-R BS.775 style coefficients (fronts 1.0, centre and surrounds −3 dB,
 * back centre −6 dB, LFE dropped) in Android's channel order, then scales every row so its absolute
 * coefficients sum to at most 1: a full-scale signal on every channel at once still stays within
 * ±1.0, so nothing can clip. Mono/stereo and non-PCM input leave it inactive.
 */
class SafeDownmixProcessor : BaseAudioProcessor() {

    private var matrix: Array<FloatArray> = emptyArray()
    private var channels = 0
    private var encoding = C.ENCODING_INVALID

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val pcm = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val coefficients = stereoMatrix(inputAudioFormat.channelCount)
        if (!pcm || coefficients == null) return AudioProcessor.AudioFormat.NOT_SET
        matrix = coefficients
        channels = inputAudioFormat.channelCount
        encoding = inputAudioFormat.encoding
        return AudioProcessor.AudioFormat(inputAudioFormat.sampleRate, STEREO, inputAudioFormat.encoding)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytesPerSample = if (encoding == C.ENCODING_PCM_FLOAT) FLOAT_BYTES else SHORT_BYTES
        val frameBytes = channels * bytesPerSample
        if (frameBytes == 0 || !inputBuffer.hasRemaining()) return
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val frames = input.remaining() / frameBytes
        val output = replaceOutputBuffer(frames * STEREO * bytesPerSample).order(ByteOrder.nativeOrder())
        val frame = FloatArray(channels)
        repeat(frames) {
            for (c in 0 until channels) frame[c] = read(input)
            for (row in matrix) {
                var mixed = 0f
                for (c in 0 until channels) mixed += row[c] * frame[c]
                write(output, mixed)
            }
        }
        input.position(input.limit())
        output.flip()
    }

    private fun read(buffer: ByteBuffer): Float =
        if (encoding == C.ENCODING_PCM_FLOAT) buffer.getFloat() else buffer.getShort() / SHORT_SCALE

    private fun write(buffer: ByteBuffer, value: Float) {
        val clamped = value.coerceIn(-1f, 1f)
        if (encoding == C.ENCODING_PCM_FLOAT) {
            buffer.putFloat(clamped)
        } else {
            buffer.putShort((clamped * SHORT_SCALE).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
        }
    }

    companion object {
        private const val STEREO = 2
        private const val SHORT_SCALE = 32_768f
        private const val SHORT_BYTES = 2
        private const val FLOAT_BYTES = 4
        private const val FRONT = 1f
        private const val MINUS_3_DB = 0.7071f
        private const val MINUS_6_DB = 0.5f
        private const val LFE = 0f

        /**
         * `[left row, right row]` for [channelCount] inputs (3–8, Android order), normalised so each row's
         * absolute sum is ≤ 1; `null` for counts that need no downmix or have no standard layout.
         */
        fun stereoMatrix(channelCount: Int): Array<FloatArray>? {
            // Per input channel: (gain to left, gain to right).
            val layout: List<Pair<Float, Float>> = when (channelCount) {
                3 -> listOf(L, R, C_BOTH) // FL FR FC
                4 -> listOf(L, R, SL, SR) // FL FR BL BR
                5 -> listOf(L, R, C_BOTH, SL, SR) // FL FR FC BL BR
                6 -> listOf(L, R, C_BOTH, LFE_BOTH, SL, SR) // 5.1
                7 -> listOf(L, R, C_BOTH, LFE_BOTH, SL, SR, BC_BOTH) // 6.1
                8 -> listOf(L, R, C_BOTH, LFE_BOTH, SL, SR, SL, SR) // 7.1
                else -> return null
            }
            val left = FloatArray(channelCount) { layout[it].first }
            val right = FloatArray(channelCount) { layout[it].second }
            val scale = 1f / maxOf(left.sumOf { abs(it).toDouble() }, right.sumOf { abs(it).toDouble() }, 1.0).toFloat()
            return arrayOf(FloatArray(channelCount) { left[it] * scale }, FloatArray(channelCount) { right[it] * scale })
        }

        private val L = FRONT to 0f
        private val R = 0f to FRONT
        private val C_BOTH = MINUS_3_DB to MINUS_3_DB
        private val LFE_BOTH = LFE to LFE
        private val SL = MINUS_3_DB to 0f
        private val SR = 0f to MINUS_3_DB
        private val BC_BOTH = MINUS_6_DB to MINUS_6_DB
    }
}
