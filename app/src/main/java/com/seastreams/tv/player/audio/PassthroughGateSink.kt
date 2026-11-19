package com.seastreams.tv.player.audio

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink

/**
 * The player's one audio sink, with HDMI passthrough switchable during playback (PRD §9). While
 * [allowPassthrough] is false every encoded format (AC-3, E-AC-3, TrueHD, DTS…) reports as unsupported,
 * so the audio renderers decode it to PCM (MediaCodec, else FFmpeg) and the DSP chain applies; PCM
 * itself always goes straight to the wrapped sink. After flipping the gate, [AudioRendererReset] makes
 * the renderer pick again.
 */
class PassthroughGateSink(sink: AudioSink, allowPassthrough: Boolean) : ForwardingAudioSink(sink) {

    @Volatile
    var allowPassthrough: Boolean = allowPassthrough

    override fun supportsFormat(format: Format): Boolean = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun getFormatSupport(format: Format): Int =
        if (!allowPassthrough && format.sampleMimeType != MimeTypes.AUDIO_RAW) AudioSink.SINK_FORMAT_UNSUPPORTED else super.getFormatSupport(format)
}

/**
 * Makes the audio renderer decide again between bitstream and decoding without restarting playback:
 * audio is disabled, and re-enabled once ExoPlayer reports no audio track selected (or after
 * [FALLBACK_MS]). Both changes can't be made at once: ExoPlayer would merge them into a no-op. The
 * video keeps playing; audio drops out briefly. Main thread only.
 */
class AudioRendererReset(
    private val setAudioDisabled: (Boolean) -> Unit,
    private val audioSelected: () -> Boolean,
    private val schedule: (delayMs: Long, action: () -> Unit) -> Unit,
) {
    var pending: Boolean = false
        private set

    fun request() {
        if (pending) return
        pending = true
        setAudioDisabled(true)
        schedule(FALLBACK_MS) { finish() }
    }

    /** Call from `onTracksChanged`. */
    fun onTracksChanged() {
        if (pending && !audioSelected()) finish()
    }

    private fun finish() {
        if (!pending) return
        pending = false
        setAudioDisabled(false)
    }

    companion object {
        const val FALLBACK_MS = 500L
    }
}
