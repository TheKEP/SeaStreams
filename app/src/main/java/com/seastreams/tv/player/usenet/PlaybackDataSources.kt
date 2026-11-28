package com.seastreams.tv.player.usenet

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import com.seastreams.tv.data.debrid.stream.DebridLinkResolver
import com.seastreams.tv.data.debrid.stream.DebridStreamRegistry
import com.seastreams.tv.domain.model.StreamSource
import com.seastreams.tv.usenet.session.UsenetSessionRegistry
import com.seastreams.tv.usenet.session.UsenetStreamRegistry
import dagger.Lazy
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Picks how the built-in player reads a stream by its URI scheme (ARCHITECTURE §10, §12), called from
 * `SeaStreamsPlayerManager.playStream`:
 * - `seastreams-nntp://` → [NntpDataSource] with [UsenetLoadErrorPolicy];
 * - `seastreams-debrid://` → the given HTTP factory (the parallel range reader) behind a [ResolvingDataSource] that
 *   swaps the token for its HTTPS link on every open ([DebridLinkResolver], cached 20 min, so the link the preflight
 *   resolved is the one played and a seek past the cache gets a fresh one);
 * - anything else → the given HTTP factory with the default retries.
 */
@Singleton
class PlaybackDataSources @Inject constructor(
    private val sessions: UsenetSessionRegistry,
    /** Lazy: resolved on the loader thread, never during player creation. */
    private val debridLinks: Lazy<DebridLinkResolver>,
) {

    /** [wrap] is applied to the outermost factory (e.g. the Info overlay's file-size tracker). */
    fun mediaSourceFactory(
        stream: StreamSource,
        http: () -> DataSource.Factory,
        listener: TransferListener,
        wrap: (DataSource.Factory) -> DataSource.Factory = { it },
    ): MediaSource.Factory = when {
        UsenetStreamRegistry.isUsenet(stream.url) ->
            DefaultMediaSourceFactory(wrap(NntpDataSource.Factory(sessions, listener))).setLoadErrorHandlingPolicy(UsenetLoadErrorPolicy())
        DebridStreamRegistry.isDebrid(stream.url) -> DefaultMediaSourceFactory(wrap(ResolvingDataSource.Factory(http(), ::resolveDebrid)))
        else -> DefaultMediaSourceFactory(wrap(http()))
    }

    /**
     * A plain reader for small header probes (Matroska chapters, PRD §9 skip prompts) beside playback: Debrid
     * tokens resolve exactly as in [mediaSourceFactory]; `null` for Usenet, whose connections belong to playback.
     */
    fun probeFactory(stream: StreamSource, http: DataSource.Factory): DataSource.Factory? = when {
        UsenetStreamRegistry.isUsenet(stream.url) -> null
        DebridStreamRegistry.isDebrid(stream.url) -> ResolvingDataSource.Factory(http, ::resolveDebrid)
        else -> http
    }

    /** `seastreams-debrid://<token>` → its HTTPS link; a refused or unknown token fails the load (failover moves on). */
    @Throws(IOException::class)
    private fun resolveDebrid(spec: DataSpec): DataSpec {
        val token = DebridStreamRegistry.tokenOf(spec.uri.toString()) ?: return spec
        return spec.withUri(Uri.parse(debridLinks.get().resolveBlocking(token)))
    }
}
