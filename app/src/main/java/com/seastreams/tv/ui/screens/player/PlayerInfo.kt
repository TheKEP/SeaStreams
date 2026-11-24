package com.seastreams.tv.ui.screens.player

import android.net.Uri
import androidx.media3.common.Format
import com.seastreams.tv.core.util.MILLIS_PER_SECOND
import com.seastreams.tv.domain.model.PlaybackRequest
import com.seastreams.tv.player.PlayerTracks
import com.seastreams.tv.player.SeaStreamsPlayerManager
import com.seastreams.tv.player.VideoDynamicRange
import com.seastreams.tv.player.subtitle.sync.syncInfoText
import com.seastreams.tv.usenet.session.UsenetStreamRegistry
import java.util.Locale

/** The live player values the Info overlay shows, read once per refresh (so [infoRows] stays pure). */
data class InfoTelemetry(
    val video: Format? = null,
    val audio: Format? = null,
    val dynamicRange: String = "—",
    val downloadMbps: Double = 0.0,
    val activeConnections: Int = 0,
    val maxConnections: Int = 0,
    val bufferedSeconds: Double = 0.0,
    val bufferedMb: Double = 0.0,
    /** The playing file's size as its data source reported it, `null` while unknown. */
    val contentLengthBytes: Long? = null,
    /** ExoPlayer's duration; `C.TIME_UNSET` (negative) while unknown. */
    val playerDurationMs: Long = 0,
)

/** Reads [manager]'s live values and builds the Info rows (PRD §9). */
fun buildInfoRows(state: PlayerUiState, manager: SeaStreamsPlayerManager): List<InfoRow> {
    val player = manager.player
    val video = player.videoFormat
    val telemetry = InfoTelemetry(
        video = video,
        audio = player.audioFormat,
        dynamicRange = VideoDynamicRange.label(PlayerTracks.selectedVideoFormat(player.currentTracks), video, manager.config.dolbyVision),
        downloadMbps = manager.meter.megabitsPerSecond(),
        activeConnections = manager.meter.activeConnections.get().coerceAtLeast(0),
        maxConnections = manager.config.parallelConnections,
        bufferedSeconds = player.totalBufferedDuration / MILLIS_PER_SECOND.toDouble(),
        bufferedMb = manager.bufferedBytes / BYTES_PER_MB,
        contentLengthBytes = manager.contentLength.bytes,
        playerDurationMs = player.duration,
    )
    return infoRows(state, telemetry)
}

/**
 * Rows of the Info overlay (PRD §9), in order: stream rank/label and engine; the live network rows (download
 * throughput, parallel range connections, buffer health); then file name, video and audio formats and the active
 * subtitle. `Range connections` is left out for Usenet releases, which are read over NNTP, not HTTP ranges.
 */
internal fun infoRows(state: PlayerUiState, telemetry: InfoTelemetry): List<InfoRow> {
    val video = telemetry.video
    val audio = telemetry.audio
    val request = state.request
    val stream = state.streams.getOrNull(state.streamIndex)
    val runtimeMs = (request as? PlaybackRequest.Media)?.target?.knownDurationMs ?: 0L
    return buildList {
        if (request is PlaybackRequest.Media && stream != null) {
            add(InfoRow("Stream", "#${state.streamIndex + 1} of ${state.streams.size} · ${stream.label}"))
        } else {
            add(InfoRow("Stream", "YouTube trailer"))
        }
        add(InfoRow("Player engine", "Media3 ExoPlayer"))
        add(InfoRow("Download", "%.1f Mbps".format(Locale.US, telemetry.downloadMbps)))
        if (stream == null || !UsenetStreamRegistry.isUsenet(stream.url)) {
            add(InfoRow("Range connections", "${telemetry.activeConnections}x active · ${telemetry.maxConnections}x max"))
        }
        add(InfoRow("Buffer", "%.1f s · %.1f MB".format(Locale.US, telemetry.bufferedSeconds, telemetry.bufferedMb)))
        // One size for both rows: data source → addon/indexer/Debrid hint → parsed release.
        val size = streamSizeBytes(telemetry.contentLengthBytes, stream)
        add(InfoRow("File", fileName(stream?.description, stream?.url) ?: "—"))
        add(InfoRow("File size", fileSizeText(size)))
        add(InfoRow("Video codec", videoCodec(video)))
        add(InfoRow("Dynamic range", telemetry.dynamicRange))
        add(InfoRow("Resolution", video?.takeIf { it.width > 0 }?.let { "${it.width}×${it.height}${frameRate(it)}" } ?: "—"))
        add(InfoRow("Bitrate", bitrateText(video, size, playbackDurationMs(telemetry.playerDurationMs, runtimeMs))))
        add(InfoRow("Audio codec", PlayerTracks.codecName(audio?.sampleMimeType) ?: "—"))
        add(InfoRow("Channels", audio?.let { PlayerTracks.channelLayout(it.channelCount) } ?: "—"))
        add(InfoRow("Sample rate", audio?.sampleRate?.takeIf { it > 0 }?.let { "%.1f kHz".format(Locale.US, it / HZ_PER_KHZ) } ?: "—"))
        add(InfoRow("Audio language", PlayerTracks.languageName(audio?.language) ?: "—"))
        addAll(subtitleInfoRows(state))
    }
}

/**
 * The subtitle rows, read only from the session's chosen track ([PlayerUiState.selectedSubtitle]),
 * never from ExoPlayer's text selection: auto-sync's background embedded reference track can't show
 * up here. An active addon track also lists its sync offset.
 */
internal fun subtitleInfoRows(state: PlayerUiState): List<InfoRow> {
    val subtitle = state.selectedSubtitle
    return listOfNotNull(
        InfoRow("Subtitles", subtitle?.label ?: "Off"),
        InfoRow("Subtitle language", PlayerTracks.languageName(subtitle?.language) ?: subtitle?.language ?: "—"),
        InfoRow("Subtitle source", subtitle?.source?.label ?: "—"),
        if (state.externalSubtitleActive) InfoRow("Subtitle sync", syncInfoText(state.subtitleOffsetMs, state.subtitleSyncStatus, state.subtitleRate)) else null,
    )
}

private fun videoCodec(format: Format?): String {
    val name = PlayerTracks.codecName(format?.sampleMimeType) ?: return "—"
    return name + format?.codecs?.let { " ($it)" }.orEmpty()
}

private fun frameRate(format: Format): String =
    format.frameRate.takeIf { it > 0 }?.let { " @ %.3g fps".format(Locale.US, it) }.orEmpty()

/** The first line of the addon's description (usually the file name), else the URL's last segment. */
private fun fileName(description: String?, url: String?): String? {
    val fromDescription = description?.lines()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }
    if (fromDescription != null) return fromDescription
    return url?.let { runCatching { Uri.parse(it).lastPathSegment }.getOrNull() }?.takeIf { it.isNotBlank() }
}

private const val BYTES_PER_MB = 1024.0 * 1024.0
private const val HZ_PER_KHZ = 1_000.0
