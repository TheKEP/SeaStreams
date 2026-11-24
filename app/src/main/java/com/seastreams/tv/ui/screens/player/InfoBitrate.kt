package com.seastreams.tv.ui.screens.player

import androidx.media3.common.Format
import com.seastreams.tv.core.util.MILLIS_PER_SECOND
import com.seastreams.tv.domain.model.StreamSource
import java.util.Locale

/**
 * The Info overlay's `Bitrate` row (PRD §9). The track's own bitrate when ExoPlayer reports one (`42.5 Mbps`);
 * otherwise, as Matroska/WebM, Usenet and Debrid files usually report none, an average over the whole file from its
 * size and duration, marked as an estimate (`42.5 Mbps (est.)`); `—` when neither is known.
 */
internal fun bitrateText(video: Format?, sizeBytes: Long?, durationMs: Long): String {
    val reported = video?.let { maxOf(it.averageBitrate, it.peakBitrate, it.bitrate) }?.takeIf { it > 0 }
    if (reported != null) return formatMbps(reported.toDouble())
    val estimated = estimateBitsPerSecond(sizeBytes, durationMs) ?: return "—"
    return formatMbps(estimated) + " (est.)"
}

/** `(fileSizeBytes × 8) ÷ durationSeconds`; `null` unless both are known and positive. */
internal fun estimateBitsPerSecond(sizeBytes: Long?, durationMs: Long): Double? {
    val size = sizeBytes?.takeIf { it > 0 } ?: return null
    if (durationMs <= 0) return null
    return size * BITS_PER_BYTE / (durationMs / MILLIS_PER_SECOND.toDouble())
}

/**
 * The best known size of the playing file: what its data source reported ([trackedBytes]), else the addon's
 * `videoSize` / the indexer's or Debrid service's size ([StreamSource.hints]), else the size parsed from the release text.
 */
internal fun streamSizeBytes(trackedBytes: Long?, stream: StreamSource?): Long? =
    trackedBytes?.takeIf { it > 0 }
        ?: stream?.hints?.sizeBytes?.takeIf { it > 0 }
        ?: stream?.parsed?.sizeBytes?.takeIf { it > 0 }

/** ExoPlayer's duration once known, else the TMDB runtime ([fallbackMs], `0` when unknown). */
internal fun playbackDurationMs(playerDurationMs: Long, fallbackMs: Long): Long =
    playerDurationMs.takeIf { it > 0 } ?: fallbackMs.coerceAtLeast(0)

private fun formatMbps(bitsPerSecond: Double): String = "%.1f Mbps".format(Locale.US, bitsPerSecond / BITS_PER_MEGABIT)

private const val BITS_PER_BYTE = 8.0
private const val BITS_PER_MEGABIT = 1_000_000.0
