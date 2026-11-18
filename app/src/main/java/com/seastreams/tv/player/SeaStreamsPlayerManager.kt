package com.seastreams.tv.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.seastreams.tv.core.network.withoutDiskCache
import com.seastreams.tv.domain.model.StreamSource
import com.seastreams.tv.domain.model.TrailerStream
import com.seastreams.tv.player.audio.AudioDspConfig
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
 * for addon streams, Dolby Vision Profile 7 → HDR10, on-device audio DSP, and `MergingMediaSource`
 * (adaptive video + M4A audio) for YouTube trailers. Main thread only; [release] when the session ends.
 *
 * Stream fallback, watch progress and auto-next are decided by the session on top of [events].
 */
class SeaStreamsPlayerManager(
    context: Context,
    okHttpClient: OkHttpClient,
    val config: PlayerEngineConfig,
) {
    /** Shared client without the 100 MB disk cache: video bytes must never be cached there. */
    private val client: OkHttpClient = okHttpClient.withoutDiskCache()
    private val engine = PlayerEngineFactory.create(context.applicationContext, config)
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
            _events.tryEmit(PlayerEvent.TracksChanged)
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                _videoAspect.value = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
            }
        }

        override fun onCues(cueGroup: CueGroup) {
            _embeddedCues.value = cueGroup.cues.map { it.text }
        }
    }

    init {
        player.addListener(listener)
    }

    /** Bytes held by the read-ahead buffer (Info overlay `buffered MB`). */
    val bufferedBytes: Long get() = engine.buffer.allocator.totalBytesAllocated.toLong()

    /** Plays one addon stream from [startPositionMs], with its `proxyHeaders` on every request. */
    fun playStream(stream: StreamSource, startPositionMs: Long) {
        meter.reset()
        val dataSource = ParallelRangeDataSource.Factory(client, config.parallelConnections, meter)
            .setDefaultRequestProperties(stream.headers)
        val item = MediaItem.Builder().setUri(stream.url).apply {
            if (stream.url.contains(".m3u8", ignoreCase = true)) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
        val source = DefaultMediaSourceFactory(dataSource).createMediaSource(item)
        _embeddedCues.value = emptyList()
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
        player.setMediaSource(trailerMediaSource(stream, dataSource))
        player.prepare()
        player.playWhenReady = true
    }

    fun setDsp(config: AudioDspConfig) {
        engine.dsp?.config = config
    }

    /** Whether on-device DSP is available (passthrough off). */
    val dspAvailable: Boolean get() = engine.dsp != null

    fun stop() {
        player.stop()
        player.clearMediaItems()
    }

    fun release() {
        player.removeListener(listener)
        player.release()
    }

    private companion object {
        const val EVENT_BUFFER = 32
        const val DEFAULT_ASPECT = 16f / 9f
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
