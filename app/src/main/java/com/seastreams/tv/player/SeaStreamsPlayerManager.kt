package com.seastreams.tv.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.seastreams.tv.core.network.withoutDiskCache
import com.seastreams.tv.domain.model.StreamSource
import com.seastreams.tv.domain.model.TrailerStream
import com.seastreams.tv.player.audio.AudioDspConfig
import com.seastreams.tv.player.audio.AudioRendererReset
import com.seastreams.tv.player.subtitle.sync.AutoSyncSource
import com.seastreams.tv.player.subtitle.sync.ReferenceCueCollector
import com.seastreams.tv.player.subtitle.sync.ReferenceSnapshot
import com.seastreams.tv.player.subtitle.sync.SpeechEnergyLog
import com.seastreams.tv.player.subtitle.sync.hasVisibleCue
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import java.io.IOException

/** What the player reports to the session. */
sealed interface PlayerEvent {
    data object Ready : PlayerEvent

    /** The current stream drew its first video frame: initial loading is over (PRD §9). */
    data object FirstFrame : PlayerEvent

    data object Ended : PlayerEvent

    data class Failed(val error: PlaybackException) : PlayerEvent

    data object TracksChanged : PlayerEvent
}

/**
 * The built-in Media3 ExoPlayer (PRD §9): buffer profile `LoadControl`, the parallel HTTP range reader
 * for addon streams, Dolby Vision Profile 7 → HDR10, FFmpeg audio fallback + on-device audio DSP,
 * `MergingMediaSource` (adaptive video + M4A audio) for YouTube trailers, and the timing sources of
 * subtitle auto-sync ([AutoSyncSource]). Main thread only; [release] when the session ends.
 *
 * Stream fallback, watch progress and auto-next are decided by the session on top of [events].
 */
class SeaStreamsPlayerManager(
    context: Context,
    okHttpClient: OkHttpClient,
    val config: PlayerEngineConfig,
) : AutoSyncSource {
    /** Shared client without the 100 MB disk cache: video bytes must never be cached there. */
    private val client: OkHttpClient = okHttpClient.withoutDiskCache()
    override val speechLog = SpeechEnergyLog()
    private val referenceCues = ReferenceCueCollector()
    private val engine = PlayerEngineFactory.create(context.applicationContext, config, speechLog)
    val player get() = engine.player
    val trackSelector get() = engine.trackSelector
    val meter = ThroughputMeter()

    private val _events = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = EVENT_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<PlayerEvent> = _events.asSharedFlow()

    private val _videoAspect = MutableStateFlow(DEFAULT_ASPECT)
    val videoAspect: StateFlow<Float> = _videoAspect.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _buffering = MutableStateFlow(true)
    val buffering: StateFlow<Boolean> = _buffering.asStateFlow()

    /** Embedded-track cue texts (external tracks are timed by the session instead). */
    private val _embeddedCues = MutableStateFlow<List<CharSequence?>>(emptyList())
    val embeddedCues: StateFlow<List<CharSequence?>> = _embeddedCues.asStateFlow()

    /** Whether audio currently leaves as PCM (so the DSP applies); `false` while bitstreaming. */
    private val _pcmOutput = MutableStateFlow(!config.passthrough)
    val pcmOutput: StateFlow<Boolean> = _pcmOutput.asStateFlow()

    /** SDR / HDR10 / HLG / Dolby Vision of the current stream (PRD §10); `SDR` until its video is known. */
    private val _dynamicRange = MutableStateFlow(DynamicRange.SDR)
    val dynamicRange: StateFlow<DynamicRange> = _dynamicRange.asStateFlow()
    private var videoTrackFormat: Format? = null
    private var videoDecoderFormat: Format? = null

    private fun updateDynamicRange(track: Format? = videoTrackFormat, decoderInput: Format? = videoDecoderFormat) {
        videoTrackFormat = track
        videoDecoderFormat = decoderInput
        _dynamicRange.value = VideoDynamicRange.ofSession(track, decoderInput)
    }

    private val analytics = object : AnalyticsListener {
        override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
            _pcmOutput.value = Util.isEncodingLinearPcm(audioTrackConfig.encoding)
        }

        override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
            updateDynamicRange(decoderInput = format)
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            _buffering.value = playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_IDLE
            when (playbackState) {
                Player.STATE_READY -> _events.tryEmit(PlayerEvent.Ready)
                Player.STATE_ENDED -> _events.tryEmit(PlayerEvent.Ended)
                else -> Unit
            }
        }

        override fun onRenderedFirstFrame() {
            _events.tryEmit(PlayerEvent.FirstFrame)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
        }

        override fun onPlayerError(error: PlaybackException) {
            _events.tryEmit(PlayerEvent.Failed(error))
        }

        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            audioReset.onTracksChanged()
            updateDynamicRange(track = PlayerTracks.selectedVideoFormat(tracks))
            _events.tryEmit(PlayerEvent.TracksChanged)
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                _videoAspect.value = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
            }
        }

        override fun onCues(cueGroup: CueGroup) {
            _embeddedCues.value = cueGroup.cues.map { it.text }
            referenceCues.onCues(cueGroup.presentationTimeUs, hasVisibleCue(cueGroup.cues), player.currentPosition * MICROS_PER_MILLI)
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
                referenceCues.onDiscontinuity()
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioReset = AudioRendererReset(
        setAudioDisabled = { disabled ->
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, disabled).build()
        },
        audioSelected = { PlayerTracks.selectedAudioId(player.currentTracks) != null },
        schedule = { delayMs, action -> mainHandler.postDelayed(action, delayMs) },
    )

    /**
     * Switches passthrough during playback: the gate changes what the sink accepts, then the audio
     * renderer re-decides (bitstream vs. decode to PCM) without restarting the video.
     */
    fun setPassthrough(enabled: Boolean) {
        if (engine.passthroughGate.allowPassthrough == enabled) return
        engine.passthroughGate.allowPassthrough = enabled
        if (player.currentTracks.isEmpty) return
        audioReset.request()
    }

    init {
        player.addListener(listener)
        player.addAnalyticsListener(analytics)
    }

    /**
     * Auto-sync's reference: plays embedded text track [id] without drawing it (the overlay ignores
     * embedded cues while an external track is active, and the HUD/Info read the session's chosen
     * track, not ExoPlayer's). Only called while an external track is the active subtitle.
     */
    override fun setReferenceTrack(id: String?) {
        if (id == null && !referenceCues.active) return
        referenceCues.setActive(id != null)
        PlayerTracks.selectEmbeddedText(player, id)
    }

    override fun referenceSnapshot(): ReferenceSnapshot = referenceCues.snapshot()

    override fun setSpeechCollecting(on: Boolean) {
        engine.speechTap.collecting = on
        if (!on) speechLog.clear()
    }

    /** Bytes held by the read-ahead buffer (Info overlay `buffered MB`). */
    val bufferedBytes: Long get() = engine.buffer.allocator.totalBytesAllocated.toLong()

    /** Plays one addon stream from [startPositionMs], with its `proxyHeaders` on every request. */
    fun playStream(stream: StreamSource, startPositionMs: Long) {
        meter.reset()
        val readAhead = config.bufferProfile.rangeReadAhead(config.parallelConnections)
        val dataSource = ParallelRangeDataSource.Factory(client, config.parallelConnections, readAhead, meter)
            .setDefaultRequestProperties(stream.headers)
        val item = MediaItem.Builder().setUri(stream.url).apply {
            if (stream.url.contains(".m3u8", ignoreCase = true)) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
        val source = DefaultMediaSourceFactory(dataSource).createMediaSource(item)
        _embeddedCues.value = emptyList()
        updateDynamicRange(track = null, decoderInput = null)
        player.setMediaSource(source, startPositionMs.coerceAtLeast(0))
        player.prepare()
        player.playWhenReady = true
    }

    /** Plays a trailer: DASH/progressive video merged with its separate audio track when there is one. */
    @Throws(IOException::class)
    fun playTrailer(stream: TrailerStream) {
        meter.reset()
        val dataSource = OkHttpDataSource.Factory(client).setTransferListener(MeterTransferListener(meter))
        player.volume = 1f
        updateDynamicRange(track = null, decoderInput = null)
        player.setMediaSource(trailerMediaSource(stream, dataSource))
        player.prepare()
        player.playWhenReady = true
    }

    fun setDsp(config: AudioDspConfig) {
        engine.dsp.config = config
    }

    /**
     * Ends playback (PRD §10): the video surface is detached from the decoder first, so its last
     * HDR/Dolby Vision frame is no longer fed or held for the display, then the decoders are stopped.
     */
    fun stop() {
        player.clearVideoSurface()
        player.stop()
        player.clearMediaItems()
        updateDynamicRange(track = null, decoderInput = null)
    }

    fun release() {
        mainHandler.removeCallbacksAndMessages(null)
        player.removeListener(listener)
        player.removeAnalyticsListener(analytics)
        player.clearVideoSurface()
        player.release()
    }

    private companion object {
        const val EVENT_BUFFER = 32
        const val DEFAULT_ASPECT = 16f / 9f
        const val MICROS_PER_MILLI = 1_000L
    }
}

/** Feeds a plain OkHttp data source's bytes into a [ThroughputMeter] (trailers, inline previews). */
class MeterTransferListener(private val meter: ThroughputMeter) : androidx.media3.datasource.TransferListener {
    override fun onTransferInitializing(source: DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean) = Unit

    override fun onTransferStart(source: DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean) {
        meter.activeConnections.incrementAndGet()
    }

    override fun onBytesTransferred(source: DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
        meter.record(bytesTransferred)
    }

    override fun onTransferEnd(source: DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean) {
        meter.activeConnections.decrementAndGet()
    }
}

/** ExoPlayer reports `C.TIME_UNSET` for unknown durations. */
fun Long.knownOrZero(): Long = if (this == C.TIME_UNSET || this < 0) 0 else this
