package com.seastreams.tv.player

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.seastreams.tv.domain.model.TrailerSource
import com.seastreams.tv.domain.model.TrailerStream
import java.io.IOException

/**
 * A YouTube trailer as one ExoPlayer source: the adaptive video and its separate M4A audio joined
 * with `MergingMediaSource`, or the single muxed stream. Parts with a locally built DASH manifest
 * play through `DashMediaSource` (short ranged requests, as YouTube expects); others progressively.
 */
@Throws(IOException::class)
fun trailerMediaSource(stream: TrailerStream, dataSource: DataSource.Factory): MediaSource {
    val video = trailerPartSource(stream.video, dataSource)
    val audio = stream.audio ?: return video
    return MergingMediaSource(video, trailerPartSource(audio, dataSource))
}

private fun trailerPartSource(part: TrailerSource, dataSource: DataSource.Factory): MediaSource {
    val manifest = part.dashManifest ?: return ProgressiveMediaSource.Factory(dataSource).createMediaSource(MediaItem.fromUri(part.url))
    val uri = Uri.parse(part.url)
    val parsed = DashManifestParser().parse(uri, manifest.byteInputStream())
    return DashMediaSource.Factory(dataSource).createMediaSource(
        parsed,
        MediaItem.Builder().setUri(uri).setMimeType(MimeTypes.APPLICATION_MPD).build(),
    )
}
