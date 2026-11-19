package com.seastreams.tv.player

import android.util.Log
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer

/**
 * How the main player turns audio into sound (ARCHITECTURE.md §5):
 *
 * - **Renderers:** Media3's `MediaCodecAudioRenderer` first (HDMI passthrough when it is on and the sink
 *   takes the codec, else a hardware decoder), then NextLib's FFmpeg renderer as the software fallback.
 *   `DefaultTrackSelector` keeps the first renderer that handles a track, so FFmpeg only decodes what
 *   nothing else can (TrueHD, DTS, DTS-HD, and AC-3/E-AC-3 on devices without a Dolby decoder).
 * - **PCM processors**, on both sinks: speech tap → clip-safe stereo downmix (stereo outputs only) →
 *   Dialogue Boost / Night Mode / Audio Delay. `DefaultAudioSink` runs them on PCM only, so bitstreamed
 *   audio is never touched.
 */
object AudioRendererPolicy {

    const val EXTENSION_MODE: Int = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON

    /** Codecs that need FFmpeg when they can be neither bitstreamed nor hardware-decoded. */
    val SOFTWARE_FALLBACK_MIMES: Set<String> = setOf(
        MimeTypes.AUDIO_AC3,
        MimeTypes.AUDIO_E_AC3,
        MimeTypes.AUDIO_E_AC3_JOC,
        MimeTypes.AUDIO_TRUEHD,
        MimeTypes.AUDIO_DTS,
        MimeTypes.AUDIO_DTS_HD,
        MimeTypes.AUDIO_DTS_EXPRESS,
    )

    /** Downmix in-app only when the connected output reports at most two PCM channels. */
    fun downmixToStereo(deviceMaxPcmChannels: Int): Boolean = deviceMaxPcmChannels in 1..STEREO

    /** The PCM processor chain, in order. */
    fun processorChain(tap: AudioProcessor, downmix: AudioProcessor?, dsp: AudioProcessor): Array<AudioProcessor> =
        listOfNotNull(tap, downmix, dsp).toTypedArray()

    /**
     * Adds the software audio renderer built by [create] to [out]: after the renderers added since
     * [firstAudioIndex] for `ON`, before them for `PREFER`, not at all for `OFF`. A missing or broken
     * native library never fails playback; the player then keeps its MediaCodec renderers only.
     */
    fun addSoftwareRenderer(out: MutableList<Renderer>, firstAudioIndex: Int, mode: Int, create: () -> Renderer): Boolean {
        if (mode == DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF) return false
        val renderer = try {
            create()
        } catch (e: LinkageError) {
            Log.w(TAG, "FFmpeg audio renderer unavailable", e)
            return false
        }
        if (mode == DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER) out.add(firstAudioIndex.coerceIn(0, out.size), renderer) else out.add(renderer)
        return true
    }

    private const val STEREO = 2
    private const val TAG = "AudioRendererPolicy"
}
