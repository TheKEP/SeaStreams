package com.seastreams.tv.ui.screens.settings

import com.seastreams.tv.core.datastore.PlayerSettings
import com.seastreams.tv.core.datastore.SettingsDataStore
import com.seastreams.tv.core.datastore.SubtitleEdgeType

/** `Calculating…` while measuring, `0 MB` when (practically) empty, otherwise `N MB used`. */
internal fun cacheSizeLabel(bytes: Long?): String {
    if (bytes == null) return "Calculating…"
    val mb = (bytes + BYTES_PER_MB / 2) / BYTES_PER_MB
    return if (mb == 0L) "0 MB" else "$mb MB used"
}

/** Subtitle of the Automatic stream failover row (PRD §11). */
internal fun failoverDescription(enabled: Boolean): String =
    if (enabled) "On: tries the next stream when one fails or doesn't start in time" else "Off: a failed stream stops; pick another from Streams"

/** Click on the timeout row: next option, wrapping 10s → 15s → 20s → 10s. */
internal fun nextFallbackTimeout(seconds: Int): Int {
    val options = PlayerSettings.FALLBACK_TIMEOUT_OPTIONS
    val index = options.indexOf(SettingsDataStore.clampFallbackTimeout(seconds))
    return options[(index + 1) % options.size]
}

/** Click on the trailer delay row: next second, wrapping 7s → 1s. */
internal fun nextTrailerDelay(seconds: Int): Int {
    val range = PlayerSettings.TRAILER_DELAY_RANGE
    return if (seconds >= range.last) range.first else seconds + 1
}

/** `Off (1x)`, `2x` … `4x` (PRD §11). */
internal fun parallelLabel(connections: Int): String = if (connections <= 1) "Off (1x)" else "${connections}x"

internal fun dialogueLabel(gainDb: Int): String = if (gainDb == 0) "Off" else "+$gainDb dB"

internal fun audioDelayLabel(delayMs: Int): String = when {
    delayMs > 0 -> "+$delayMs ms"
    delayMs < 0 -> "$delayMs ms"
    else -> "0 ms"
}

internal fun styleSummary(fontScale: Float, edge: SubtitleEdgeType): String =
    "${(fontScale * 100).toInt()}% · ${edgeLabel(edge)}"

internal fun edgeLabel(edge: SubtitleEdgeType): String = when (edge) {
    SubtitleEdgeType.NONE -> "No edge"
    SubtitleEdgeType.OUTLINE -> "Outline"
    SubtitleEdgeType.DROP_SHADOW -> "Drop shadow"
}

private const val BYTES_PER_MB = 1024L * 1024
