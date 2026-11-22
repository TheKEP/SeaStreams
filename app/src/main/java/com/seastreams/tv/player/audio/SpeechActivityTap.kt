package com.seastreams.tv.player.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import com.seastreams.tv.player.subtitle.sync.CELL_US
import com.seastreams.tv.player.subtitle.sync.SpeechEnergyLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10

/**
 * Non-intrusive PCM tap for subtitle auto-sync (PRD §9): audio passes through byte for byte, and while
 * [collecting] each 100 ms media-time cell's voice-band (300 Hz–3.4 kHz) energy and voice-band share
 * are written to [log]. Dialogue is read from the centre channel of multichannel audio, else from the
 * mid of L/R. Media time comes from the stream's `positionOffsetUs` at each flush plus frames heard,
 * so seeks and sink latency never skew it. First in the processor chain: Audio Delay can't shift it.
 */
class SpeechActivityTap(private val log: SpeechEnergyLog) : BaseAudioProcessor() {

    @Volatile
    var collecting: Boolean = false

    private var channels = 0
    private var sampleRate = 0
    private var encoding = C.ENCODING_INVALID
    private var bandpass = Biquad.bandpass(DEFAULT_RATE, VOCAL_CENTER_HZ, VOCAL_Q)
    private var positionOffsetUs = 0L
    private var framesSinceFlush = 0L
    private var cell = 0L
    private var nextBoundaryFrame = 0L
    private var cellFrames = 0
    private var bandEnergy = 0.0
    private var totalEnergy = 0.0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val pcm = PcmSamples.isSupported(inputAudioFormat.encoding)
        if (!pcm || inputAudioFormat.channelCount <= 0) return AudioProcessor.AudioFormat.NOT_SET
        return inputAudioFormat
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        channels = inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate
        encoding = inputAudioFormat.encoding
        bandpass = Biquad.bandpass(sampleRate, VOCAL_CENTER_HZ, VOCAL_Q)
        positionOffsetUs = streamMetadata.positionOffsetUs
        framesSinceFlush = 0
        cell = Math.floorDiv(positionOffsetUs, CELL_US)
        nextBoundaryFrame = framesUntil(cell + 1)
        resetCell()
    }

    override fun onReset() {
        channels = 0
        sampleRate = 0
        resetCell()
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytesPerSample = PcmSamples.bytesPerSample(encoding)
        val frameBytes = channels * bytesPerSample
        if (frameBytes == 0 || !inputBuffer.hasRemaining()) return
        val frames = inputBuffer.remaining() / frameBytes
        if (collecting) analyze(inputBuffer.duplicate().order(ByteOrder.nativeOrder()), frames, bytesPerSample) else skip(frames)
        val output = replaceOutputBuffer(inputBuffer.remaining())
        output.put(inputBuffer)
        output.flip()
    }

    private fun analyze(buffer: ByteBuffer, frames: Int, bytesPerSample: Int) {
        val dialogue = if (channels >= CENTER_MIN_CHANNELS) DialogueNightAudioProcessor.CENTER_INDEX else -1
        repeat(frames) {
            val frameStart = buffer.position()
            val sample = when {
                dialogue >= 0 -> read(buffer, frameStart + dialogue * bytesPerSample)
                channels == 1 -> read(buffer, frameStart)
                else -> (read(buffer, frameStart) + read(buffer, frameStart + bytesPerSample)) * HALF
            }
            buffer.position(frameStart + channels * bytesPerSample)
            val band = bandpass.process(sample)
            bandEnergy += band * band
            totalEnergy += sample * sample
            cellFrames++
            advanceFrame()
        }
    }

    /** Not collecting: only keep the clock running (a partial cell is never logged). */
    private fun skip(frames: Int) {
        repeat(frames) { advanceFrame() }
        resetCell()
    }

    private fun advanceFrame() {
        framesSinceFlush++
        if (framesSinceFlush < nextBoundaryFrame) return
        val full = framesUntil(cell + 1) - framesUntil(cell)
        if (cellFrames > 0 && cellFrames * 2 >= full) {
            val db = (DB_PER_DECADE * log10(bandEnergy / cellFrames + ENERGY_FLOOR)).toFloat()
            val ratio = if (totalEnergy > 0) (bandEnergy / totalEnergy).toFloat().coerceIn(0f, 1f) else 0f
            log.record(cell, db, ratio)
        }
        cell++
        nextBoundaryFrame = framesUntil(cell + 1)
        resetCell()
    }

    /** Frames since the flush at which media time reaches the start of [targetCell]. */
    private fun framesUntil(targetCell: Long): Long {
        val us = targetCell * CELL_US - positionOffsetUs
        return if (sampleRate <= 0) Long.MAX_VALUE else Math.floorDiv(us * sampleRate + MICROS_PER_SECOND - 1, MICROS_PER_SECOND)
    }

    private fun resetCell() {
        cellFrames = 0
        bandEnergy = 0.0
        totalEnergy = 0.0
    }

    private fun read(buffer: ByteBuffer, index: Int): Float = PcmSamples.read(buffer, index, encoding)

    private companion object {
        const val CENTER_MIN_CHANNELS = 3
        const val DEFAULT_RATE = 48_000
        const val VOCAL_CENTER_HZ = 1_000f
        const val VOCAL_Q = 0.45f
        const val HALF = 0.5f
        const val DB_PER_DECADE = 10.0
        const val ENERGY_FLOOR = 1e-12
        const val MICROS_PER_SECOND = 1_000_000L
    }
}
