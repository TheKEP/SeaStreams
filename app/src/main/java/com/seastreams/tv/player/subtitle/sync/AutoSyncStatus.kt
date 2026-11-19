package com.seastreams.tv.player.subtitle.sync

import com.seastreams.tv.player.subtitle.SubtitleOffset
import java.util.Locale

/** What auto-sync is timing the external subtitles against. */
enum class SyncSource(val label: String) {
    EMBEDDED("embedded subtitles"),
    AUDIO("audio"),
}

/** Live auto-sync state, shown on the HUD sync control and in the Info overlay (PRD §9). */
sealed interface AutoSyncStatus {
    /** Setting off, no external track, or not started yet. */
    data object Off : AutoSyncStatus

    /**
     * Collecting and correlating; nothing applied yet. Diagnostics for the Info overlay: [cues] (embedded:
     * reference cues received; audio: external cues in the compared window), the best candidate so far
     * even when it failed the gate ([bestOffsetMs] at Pearson [bestPeak], [onsetPercent] of cue starts on
     * onsets), and [embeddedCues] when an embedded reference was given up for audio.
     */
    data class Scanning(
        val source: SyncSource,
        val heardSeconds: Int,
        val cues: Int = 0,
        val bestOffsetMs: Long? = null,
        val bestPeak: Double? = null,
        val onsetPercent: Int? = null,
        val embeddedCues: Int? = null,
    ) : AutoSyncStatus

    /**
     * An offset was applied; [refining] while a fuller analysis may still adjust it, [tracking] while
     * drift is followed after the final lock.
     */
    data class Locked(val source: SyncSource, val refining: Boolean, val tracking: Boolean = false) : AutoSyncStatus

    /** Gave up without a confident result: the offset was left alone. */
    data object NoMatch : AutoSyncStatus

    /** The user nudged the offset: auto-sync stays out of it for this track. */
    data object Manual : AutoSyncStatus
}

/** Caption under the HUD's offset value: `Syncing…`, `Auto`, or none. */
fun AutoSyncStatus.hudCaption(): String? = when (this) {
    is AutoSyncStatus.Scanning -> SYNCING
    is AutoSyncStatus.Locked -> AUTO
    else -> null
}

/**
 * Info overlay `Subtitle sync` value, e.g. `Syncing… · embedded (0 cues), 20 s`,
 * `Syncing… · audio (18 cues, best +5.1s @ 0.42, 60% onsets), 25 s` or `+2.3s · Auto (audio) · refining`.
 */
fun syncInfoText(offsetMs: Long, status: AutoSyncStatus): String {
    val value = SubtitleOffset.label(offsetMs)
    return when (status) {
        AutoSyncStatus.Off -> value
        is AutoSyncStatus.Scanning -> scanningText(status)
        is AutoSyncStatus.Locked -> "$value · $AUTO (${status.source.label})" + when {
            status.refining -> " · refining"
            status.tracking -> " · tracking"
            else -> ""
        }
        AutoSyncStatus.NoMatch -> "$value · no confident match"
        AutoSyncStatus.Manual -> "$value · Manual"
    }
}

private fun scanningText(status: AutoSyncStatus.Scanning): String {
    val source = if (status.source == SyncSource.EMBEDDED) "embedded" else "audio"
    val cues = if (status.cues == 1) "1 cue" else "${status.cues} cues"
    val best = status.bestOffsetMs?.let { offset ->
        val peak = status.bestPeak?.let { " @ " + String.format(Locale.US, "%.2f", it) }.orEmpty()
        val onsets = status.onsetPercent?.let { ", $it% onsets" }.orEmpty()
        ", best ${SubtitleOffset.label(offset)}$peak$onsets"
    }.orEmpty()
    val gaveUp = status.embeddedCues?.let { " · embedded gave ${if (it == 1) "1 cue" else "$it cues"}" }.orEmpty()
    return "$SYNCING · $source ($cues$best), ${status.heardSeconds} s$gaveUp"
}

const val SYNCING = "Syncing…"
const val AUTO = "Auto"
