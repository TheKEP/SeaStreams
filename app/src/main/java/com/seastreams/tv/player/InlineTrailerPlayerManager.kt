package com.seastreams.tv.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import com.seastreams.tv.core.network.withoutDiskCache
import com.seastreams.tv.domain.model.TrailerStream
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one ExoPlayer shared by every catalog row's 16:9 trailer preview (PRD §4). Created on first use,
 * [release]d when the app goes to the background, so only one preview decoder ever exists. Muted
 * previews don't take audio focus. Main thread only (apart from [start]'s IO hop).
 *
 * The manager knows nothing about cards: which card owns the preview is the controller's
 * [InlineTrailerSlot] (row + index + title), and a card attaches its surface to [player] only when
 * [playsIn] holds for its slot *and* it has D-pad focus, so at most one surface is ever bound.
 */
@Singleton
class InlineTrailerPlayerManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val okHttp: Lazy<OkHttpClient>,
) : InlineTrailerPlayback {

    private val _player = MutableStateFlow<ExoPlayer?>(null)

    /** The preview player for the card's surface; `null` until a preview starts. */
    val player: StateFlow<ExoPlayer?> = _player.asStateFlow()

    private var client: OkHttpClient? = null
    private var listener: Player.Listener? = null

    override suspend fun start(stream: TrailerStream, muted: Boolean, onReady: () -> Unit, onError: () -> Unit) {
        val http = client ?: withContext(Dispatchers.IO) { okHttp.get().withoutDiskCache() }.also { client = it }
        val player = _player.value ?: ExoPlayer.Builder(context).build().also { _player.value = it }
        listener?.let(player::removeListener)
        val callbacks = object : Player.Listener {
            private var reported = false

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && !reported) {
                    reported = true
                    onReady()
                }
                // A finished preview returns the card to its artwork, like a failure (no replay loop).
                if (playbackState == Player.STATE_ENDED) onError()
            }

            override fun onPlayerError(error: PlaybackException) = onError()
        }
        listener = callbacks
        player.addListener(callbacks)
        player.setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            !muted,
        )
        player.volume = if (muted) 0f else 1f
        player.repeatMode = Player.REPEAT_MODE_OFF
        try {
            player.setMediaSource(trailerMediaSource(stream, OkHttpDataSource.Factory(http)))
        } catch (e: IOException) {
            onError()
            return
        }
        player.prepare()
        player.playWhenReady = true
    }

    override fun stop() {
        val callbacks = listener
        listener = null
        val player = _player.value ?: return
        callbacks?.let(player::removeListener)
        player.stop()
        player.clearMediaItems()
    }

    /** Frees the decoder (app paused/stopped); the next preview builds a new player. */
    fun release() {
        stop()
        _player.value?.release()
        _player.value = null
    }
}
