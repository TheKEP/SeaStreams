package com.seastreams.tv.player.usenet

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import com.seastreams.tv.domain.model.StreamSource
import com.seastreams.tv.usenet.session.UsenetSessionRegistry
import com.seastreams.tv.usenet.session.UsenetStreamRegistry
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Picks how the built-in player reads a stream by its URI scheme (ARCHITECTURE §10 ExoPlayer), called from
 * `SeaStreamsPlayerManager.playStream`: `seastreams-nntp://` → [NntpDataSource] with [UsenetLoadErrorPolicy];
 * anything else → the given HTTP factory (the parallel range reader) with the default retries.
 */
@Singleton
class PlaybackDataSources @Inject constructor(private val sessions: UsenetSessionRegistry) {

    fun isUsenet(stream: StreamSource): Boolean = UsenetStreamRegistry.isUsenet(stream.url)

    fun mediaSourceFactory(stream: StreamSource, http: () -> DataSource.Factory, listener: TransferListener): MediaSource.Factory =
        if (isUsenet(stream)) {
            DefaultMediaSourceFactory(NntpDataSource.Factory(sessions, listener)).setLoadErrorHandlingPolicy(UsenetLoadErrorPolicy())
        } else {
            DefaultMediaSourceFactory(http())
        }
}
