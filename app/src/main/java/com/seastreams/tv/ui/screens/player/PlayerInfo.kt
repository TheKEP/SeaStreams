package com.seastreams.tv.ui.screens.player

import android.net.Uri
import androidx.media3.common.Format
import com.seastreams.tv.core.util.MILLIS_PER_SECOND
import com.seastreams.tv.domain.model.PlaybackRequest
import com.seastreams.tv.player.PlayerTracks
import com.seastreams.tv.player.SeaStreamsPlayerManager
import com.seastreams.tv.player.VideoDynamicRange
import com.seastreams.tv.player.subtitle.sync.syncInfoText
import java.util.Locale

/**
 * Rows of the Info overlay (PRD §9): stream rank/label, engine, file name, video and audio formats,
 * the active subtitle, plus live throughput, parallel range connections and buffer health.
 */
fun buildInfoRows(state: PlayerUiState, manager: SeaStreamsPlayerManager): List<InfoRow> {
    val player = manager.player
    val video = player.videoFormat
    val audio = player.audioFormat
    val request = state.request
    val stream = state.streams.getOrNull(state.streamIndex)
    val bufferedSeconds = player.totalBufferedDuration / MILLIS_PER_SECOND.toDouble()
    val bufferedMb = manager.bufferedBytes / BYTES_PER_MB
    val range = VideoDynamicRange.label(PlayerTracks.selectedVideoFormat(player.currentTracks), video, manager.config.dolbyVision)
    return buildList {
        if (request is PlaybackRequest.Media && stream != null) {
            add(InfoRow("Stream", "#${state.streamIndex + 1} of ${state.streams.size} · ${stream.label}"))
        } else {
            add(InfoRow("Stream", "YouTube trailer"))
        }
        add(InfoRow("Player engine", "Media3 ExoPlayer"))
        add(InfoRow("File", fileName(stream?.description, stream?.url) ?: "—"))
        add(InfoRow("Video codec", videoCodec(video)))
        add(InfoRow("Dynamic range", range))
        add(InfoRow("Resolution", video?.takeIf { it.width > 0 }?.let { "${it.width}×${it.height}${frameRate(it)}" } ?: "—"))
        add(InfoRow("Bitrate", bitrate(video)))
        add(InfoRow("Audio codec", PlayerTracks.codecName(audio?.sampleMimeType) ?: "—"))
        add(InfoRow("Channels", audio?.let { PlayerTracks.channelLayout(it.channelCount) } ?: "—"))
        add(InfoRow("Sample rate", audio?.sampleRate?.takeIf { it > 0 }?.let { "%.1f kHz".format(Locale.US, it / HZ_PER_KHZ) } ?: "—"))
        add(InfoRow("Audio language", PlayerTracks.languageName(audio?.language) ?: "—"))
        addAll(subtitleInfoRows(state))
        add(InfoRow("Download", "%.1f Mbps".format(Locale.US, manager.meter.megabitsPerSecond())))
        add(InfoRow("Range connections", "${manager.meter.activeConnections.get().coerceAtLeast(0)}x active · ${manager.config.parallelConnections}x max"))
        add(InfoRow("Buffer", "%.1f s · %.1f MB".format(Locale.US, bufferedSeconds, bufferedMb)))
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

private fun bitrate(format: Format?): String {
    val bits = format?.let { maxOf(it.averageBitrate, it.peakBitrate, it.bitrate) }?.takeIf { it > 0 } ?: return "—"
    return "%.1f Mbps".format(Locale.US, bits / BITS_PER_MEGABIT)
}

/** The first line of the addon's description (usually the file name), else the URL's last segment. */
private fun fileName(description: String?, url: String?): String? {
    val fromDescription = description?.lines()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }
    if (fromDescription != null) return fromDescription
    return url?.let { runCatching { Uri.parse(it).lastPathSegment }.getOrNull() }?.takeIf { it.isNotBlank() }
}

private const val BYTES_PER_MB = 1024.0 * 1024.0
private const val HZ_PER_KHZ = 1_000.0
private const val BITS_PER_MEGABIT = 1_000_000.0
