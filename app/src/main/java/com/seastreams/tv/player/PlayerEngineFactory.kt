package com.seastreams.tv.player

import android.content.Context
import android.os.Handler
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import com.seastreams.tv.core.datastore.AppSettings
import com.seastreams.tv.core.datastore.PlaybackBufferProfile
import com.seastreams.tv.player.audio.AudioDspConfig
import com.seastreams.tv.player.audio.DialogueNightAudioProcessor
import com.seastreams.tv.player.audio.PassthroughGateSink
import com.seastreams.tv.player.audio.SafeDownmixProcessor
import com.seastreams.tv.player.audio.SpeechActivityTap
import com.seastreams.tv.player.subtitle.sync.SpeechEnergyLog
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegAudioRenderer

/** Everything fixed for one player session, read from General Settings when playback starts. */
data class PlayerEngineConfig(
    val bufferProfile: PlaybackBufferProfile,
    val parallelConnections: Int,
    /** Dolby Vision mode, Profile 7 toggle and display DV support (PRD §10). */
    val dolbyVision: DvPolicyInputs,
    /**
     * Passthrough on: codecs the sink takes are bitstreamed untouched. Off: everything is decoded to PCM.
     * Either way, PCM output (incl. the FFmpeg fallback) runs through the DSP.
     */
    val passthrough: Boolean,
    val dsp: AudioDspConfig,
    val preferredAudioLanguage: String,
) {
    companion object {
        fun from(settings: AppSettings, dsp: AudioDspConfig, displaySupportsDolbyVision: Boolean) = PlayerEngineConfig(
            bufferProfile = settings.player.playbackBufferProfile,
            parallelConnections = settings.player.parallelStreamConnections,
            dolbyVision = DvPolicyInputs(
                mode = settings.player.dolbyVisionPlayback,
                convertProfile7 = settings.player.convertDvProfile7ToHdr,
                displaySupportsDolbyVision = displaySupportsDolbyVision,
            ),
            passthrough = settings.audio.passthroughEnabled,
            dsp = dsp,
            preferredAudioLanguage = settings.audio.preferredAudioLanguage,
        )
    }
}

/** A built ExoPlayer plus the parts SeaStreams keeps talking to. */
class PlayerEngine(
    val player: ExoPlayer,
    val trackSelector: DefaultTrackSelector,
    val buffer: BufferedLoadControl,
    /** Acts on PCM output only (bitstreamed audio bypasses every processor). */
    val dsp: DialogueNightAudioProcessor,
    /** Subtitle auto-sync's voice-activity tap. */
    val speechTap: SpeechActivityTap,
    /** Switches HDMI passthrough during playback (session Audio overlay). */
    val passthroughGate: PassthroughGateSink,
)

/**
 * Renderers for the main player: the Dolby Vision base-layer aware video renderer, MediaCodec audio
 * followed by the FFmpeg software fallback ([AudioRendererPolicy]), and one context-aware audio sink
 * that runs [processors] on PCM behind a [PassthroughGateSink]: with passthrough off the gate reports
 * only PCM as supported, so AC-3/E-AC-3/TrueHD/DTS are decoded on-device (MediaCodec when it can, else
 * FFmpeg), and it can be flipped mid-playback.
 */
private class SeaStreamsRenderersFactory(
    context: Context,
    private val dolbyVision: DvPolicyInputs,
    private val passthrough: Boolean,
    private val processors: Array<AudioProcessor>,
) : DefaultRenderersFactory(context) {

    init {
        setEnableDecoderFallback(true)
        setExtensionRendererMode(AudioRendererPolicy.EXTENSION_MODE)
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        val firstAudioIndex = out.size
        super.buildAudioRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, audioSink, eventHandler, eventListener, out)
        AudioRendererPolicy.addSoftwareRenderer(out, firstAudioIndex, extensionRendererMode) {
            FfmpegAudioRenderer(eventHandler, eventListener, audioSink)
        }
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        val builder = MediaCodecVideoRenderer.Builder(context)
            .setCodecAdapterFactory(codecAdapterFactory)
            .setMediaCodecSelector(mediaCodecSelector)
            .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
            .setEnableDecoderFallback(enableDecoderFallback)
            .setEventHandler(eventHandler)
            .setEventListener(eventListener)
            .setMaxDroppedFramesToNotify(MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY)
        out.add(DolbyVisionCompatVideoRenderer(builder) { dolbyVision })
    }

    /** The sink [buildAudioSink] made (ExoPlayer builds it once, while the player is built). */
    var gate: PassthroughGateSink? = null
        private set

    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink {
        val sink = DefaultAudioSink.Builder(context)
            .setAudioProcessors(processors)
            .setEnableFloatOutput(false)
            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
            .build()
        return PassthroughGateSink(sink, passthrough).also { gate = it }
    }
}

object PlayerEngineFactory {

    fun create(
        context: Context,
        config: PlayerEngineConfig,
        speechLog: SpeechEnergyLog = SpeechEnergyLog(),
        handleAudioFocus: Boolean = true,
    ): PlayerEngine {
        val dsp = DialogueNightAudioProcessor(config.dsp)
        val tap = SpeechActivityTap(speechLog)
        // 16-bit output keeps FFmpeg on its saturating s16 path; stereo-only outputs get the clip-safe downmix.
        @Suppress("DEPRECATION")
        val maxPcmChannels = AudioCapabilities.getCapabilities(context).maxChannelCount
        val downmix = if (AudioRendererPolicy.downmixToStereo(maxPcmChannels)) SafeDownmixProcessor() else null
        val processors = AudioRendererPolicy.processorChain(tap, downmix, dsp)
        val trackSelector = DefaultTrackSelector(context).apply {
            parameters = buildUponParameters()
                .setPreferredAudioLanguage(config.preferredAudioLanguage)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
        }
        val buffer = buildLoadControl(config.bufferProfile.loadControlSpec())
        val renderers = SeaStreamsRenderersFactory(context, config.dolbyVision, config.passthrough, processors)
        val player = ExoPlayer.Builder(context, renderers)
            .setTrackSelector(trackSelector)
            .setLoadControl(buffer.loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                handleAudioFocus,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        return PlayerEngine(player, trackSelector, buffer, dsp, tap, checkNotNull(renderers.gate) { "audio sink not built" })
    }
}
