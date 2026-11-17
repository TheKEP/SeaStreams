package com.seastreams.tv.player

import android.content.Context
import android.os.Handler
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioCapabilities
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

/** Everything fixed for one player session, read from General Settings when playback starts. */
data class PlayerEngineConfig(
    val bufferProfile: PlaybackBufferProfile,
    val parallelConnections: Int,
    val convertDvProfile7: Boolean,
    /** Passthrough on: audio is bitstreamed and no DSP runs. Off: everything is decoded to PCM. */
    val passthrough: Boolean,
    val dsp: AudioDspConfig,
    val preferredAudioLanguage: String,
) {
    companion object {
        fun from(settings: AppSettings, dsp: AudioDspConfig) = PlayerEngineConfig(
            bufferProfile = settings.player.playbackBufferProfile,
            parallelConnections = settings.player.parallelStreamConnections,
            convertDvProfile7 = settings.player.convertDvProfile7ToHdr,
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
    /** `null` while passthrough is on. */
    val dsp: DialogueNightAudioProcessor?,
)

/**
 * Renderers for the main player: the Dolby Vision Profile 7 aware video renderer, and an audio sink
 * that, with passthrough off, only reports PCM output (so AC-3/E-AC-3/DTS are decoded on-device) and
 * runs [dsp] on it.
 */
private class SeaStreamsRenderersFactory(
    context: Context,
    private val convertDvProfile7: Boolean,
    private val passthrough: Boolean,
    private val dsp: DialogueNightAudioProcessor?,
) : DefaultRenderersFactory(context) {

    init {
        setEnableDecoderFallback(true)
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_OFF)
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
        out.add(DolbyVisionCompatVideoRenderer(builder) { convertDvProfile7 })
    }

    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink {
        if (passthrough) {
            return DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(false)
                .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                .build()
        }
        // Without a Context the builder honours explicit capabilities: PCM only, never bitstream.
        @Suppress("DEPRECATION")
        return DefaultAudioSink.Builder()
            .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
            .setAudioProcessors(listOfNotNull(dsp).toTypedArray())
            .setEnableFloatOutput(false)
            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
            .build()
    }
}

object PlayerEngineFactory {

    fun create(context: Context, config: PlayerEngineConfig, handleAudioFocus: Boolean = true): PlayerEngine {
        val dsp = if (config.passthrough) null else DialogueNightAudioProcessor(config.dsp)
        val trackSelector = DefaultTrackSelector(context).apply {
            parameters = buildUponParameters()
                .setPreferredAudioLanguage(config.preferredAudioLanguage)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
        }
        val buffer = buildLoadControl(config.bufferProfile.loadControlSpec())
        val renderers = SeaStreamsRenderersFactory(context, config.convertDvProfile7, config.passthrough, dsp)
        val player = ExoPlayer.Builder(context, renderers)
            .setTrackSelector(trackSelector)
            .setLoadControl(buffer.loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                handleAudioFocus,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        return PlayerEngine(player, trackSelector, buffer, dsp)
    }
}
