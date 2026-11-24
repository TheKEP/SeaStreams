package com.seastreams.tv.player.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import com.seastreams.tv.core.util.MILLIS_PER_SECOND
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToLong

/**
 * Live DSP settings; the player swaps them in for session-only changes from the Audio/Speed overlays.
 * [volumeGain] is the overall linear gain (Volume Boost); [playbackSpeed] is the player's current speed,
 * so Audio Delay stays [delayMs] of wall-clock time after Sonic time-stretches the output.
 */
data class AudioDspConfig(
    val dialogueBoostDb: Int = 0,
    val nightMode: Boolean = false,
    val delayMs: Int = 0,
    val volumeGain: Float = 1f,
    val playbackSpeed: Float = 1f,
)

/**
 * On-device PCM processing (PRD §9 Audio DSP & Sync), installed only when HDMI audio passthrough is
 * off, so every track is decoded to PCM first:
 *
 * - **Dialogue Boost** (`+3`–`+12 dB`): multichannel audio gets the gain on the centre channel
 *   (index 2 in Android's `FL FR FC LFE …` order); stereo and mono get it on the 300 Hz–3.4 kHz vocal
 *   band of the mid signal, so music and effects at the extremes stay where they were.
 * - **Night Mode**: a linked-channel feed-forward compressor (−30 dBFS threshold, 4:1) with make-up
 *   gain, which evens out explosions and quiet speech.
 * - **Volume Boost** (`+25%`–`+100%`): overall linear gain, applied after Dialogue Boost and Night Mode.
 * - **Audio Delay** (`±500 ms`): positive values insert silence so audio plays later than the video
 *   (ExoPlayer's clock follows the frames played); negative values drop audio so it plays earlier.
 *   This runs before Sonic (media time), so the delay is scaled by the playback speed to stay constant
 *   in wall-clock time.
 *
 * A soft limiter keeps boosted peaks from clipping. 16-bit and float PCM are processed; other
 * encodings pass through untouched (the processor reports itself inactive).
 */
class DialogueNightAudioProcessor(initial: AudioDspConfig = AudioDspConfig()) : BaseAudioProcessor() {

    @Volatile
    var config: AudioDspConfig = initial

    private var channels = 0
    private var sampleRate = 0
    private var bytesPerSample = 0
    private var encoding = C.ENCODING_INVALID
    private var bandpass: Array<Biquad> = emptyArray()
    private val compressor = Compressor()

    /** Delay (in frames) already applied since the last flush. */
    private var appliedDelayFrames = 0L
    private var silenceFramesOwed = 0L
    private var framesToSkip = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val supported = PcmSamples.isSupported(inputAudioFormat.encoding)
        if (!supported || inputAudioFormat.channelCount <= 0) return AudioProcessor.AudioFormat.NOT_SET
        return inputAudioFormat
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        channels = inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate
        encoding = inputAudioFormat.encoding
        bytesPerSample = PcmSamples.bytesPerSample(encoding)
        bandpass = Array(if (channels <= 2) 1 else 0) { Biquad.bandpass(sampleRate, VOCAL_CENTER_HZ, VOCAL_Q) }
        compressor.configure(sampleRate)
        appliedDelayFrames = 0
        silenceFramesOwed = 0
        framesToSkip = 0
    }

    override fun onReset() {
        channels = 0
        sampleRate = 0
        appliedDelayFrames = 0
        silenceFramesOwed = 0
        framesToSkip = 0
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val frameBytes = channels * bytesPerSample
        if (frameBytes == 0 || !inputBuffer.hasRemaining()) return
        val current = config
        updateDelay(current.delayMs, current.playbackSpeed)
        val inputFrames = inputBuffer.remaining() / frameBytes
        val skipped = minOf(framesToSkip, inputFrames.toLong()).toInt()
        framesToSkip -= skipped
        inputBuffer.position(inputBuffer.position() + skipped * frameBytes)
        val keptFrames = inputFrames - skipped
        val silence = silenceFramesOwed
        silenceFramesOwed = 0
        val output = replaceOutputBuffer(((silence + keptFrames) * frameBytes).toInt()).order(ByteOrder.nativeOrder())
        repeat((silence * frameBytes).toInt()) { output.put(0) }
        val gain = dbToLinear(current.dialogueBoostDb.toFloat())
        val frame = FloatArray(channels)
        repeat(keptFrames) {
            for (c in 0 until channels) frame[c] = readSample(inputBuffer)
            process(frame, gain, current.nightMode, current.volumeGain)
            for (c in 0 until channels) writeSample(output, frame[c])
        }
        // Drop any trailing partial frame so the buffer is fully consumed.
        inputBuffer.position(inputBuffer.limit())
        output.flip()
    }

    /** Applies a changed delay at once: more delay inserts silence, less delay skips input. */
    private fun updateDelay(delayMs: Int, speed: Float) {
        val target = (delayMs.toDouble() * speed.coerceAtLeast(MIN_SPEED) * sampleRate / MILLIS_PER_SECOND).roundToLong()
        val diff = target - appliedDelayFrames
        if (diff == 0L) return
        appliedDelayFrames = target
        if (diff > 0) {
            val cancelled = minOf(diff, framesToSkip)
            framesToSkip -= cancelled
            silenceFramesOwed += diff - cancelled
        } else {
            val owedCancelled = minOf(-diff, silenceFramesOwed)
            silenceFramesOwed -= owedCancelled
            framesToSkip += -diff - owedCancelled
        }
    }

    private fun process(frame: FloatArray, dialogueGain: Float, nightMode: Boolean, volumeGain: Float) {
        if (dialogueGain != 1f) {
            if (channels >= CENTER_CHANNEL_MIN_COUNT) {
                frame[CENTER_INDEX] *= dialogueGain
            } else {
                val mid = if (channels == 1) frame[0] else (frame[0] + frame[1]) * HALF
                val vocal = bandpass[0].process(mid) * (dialogueGain - 1f)
                for (c in 0 until channels) frame[c] += vocal
            }
        } else if (bandpass.isNotEmpty()) {
            // Keep the filter state warm so switching boost on mid-stream doesn't click.
            bandpass[0].process(if (channels == 1) frame[0] else (frame[0] + frame[1]) * HALF)
        }
        if (nightMode) {
            val gain = compressor.gainFor(frame.maxOf { kotlin.math.abs(it) })
            for (c in 0 until channels) frame[c] *= gain
        }
        if (volumeGain != 1f) {
            for (c in 0 until channels) frame[c] *= volumeGain
        }
        if (dialogueGain != 1f || nightMode || volumeGain != 1f) {
            for (c in 0 until channels) frame[c] = softLimit(frame[c])
        }
    }

    private fun readSample(buffer: ByteBuffer): Float = PcmSamples.read(buffer, encoding)

    private fun writeSample(buffer: ByteBuffer, value: Float) = PcmSamples.write(buffer, encoding, value)

    companion object {
        const val CENTER_INDEX = 2
        private const val CENTER_CHANNEL_MIN_COUNT = 3
        private const val VOCAL_CENTER_HZ = 1_000f
        /** Q of a band centred on 1 kHz spanning roughly 300 Hz–3.4 kHz. */
        private const val VOCAL_Q = 0.45f
        private const val HALF = 0.5f
        private const val MIN_SPEED = 0.1f
    }
}
