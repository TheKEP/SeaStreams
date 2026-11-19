package com.seastreams.tv.ui.screens.player

import androidx.lifecycle.viewModelScope
import com.seastreams.tv.domain.playback.SubtitleSource
import com.seastreams.tv.player.SeaStreamsPlayerManager
import com.seastreams.tv.player.subtitle.ExternalSubtitleTimeline
import com.seastreams.tv.player.subtitle.SubtitleOffset
import com.seastreams.tv.player.subtitle.sync.AutoSyncStatus
import com.seastreams.tv.player.subtitle.sync.ReferenceTrackPicker
import com.seastreams.tv.player.subtitle.sync.SubtitleAutoSync
import kotlinx.coroutines.flow.update

/*
 * External subtitle auto-sync for the player session (PRD §9). Detection runs in [SubtitleAutoSync];
 * this file decides when it runs and feeds the result into the HUD's `- 0.0s +` offset.
 */

/** Auto-sync bookkeeping of one session. */
internal class AutoSyncSession {
    var controller: SubtitleAutoSync? = null

    /** The user nudged the offset for this track: auto-sync stays off until another track is picked. */
    var manualOverride = false

    /** Start once the current stream's tracks are known (needed to find an embedded reference). */
    var awaitingTracks = false

    fun stop() {
        controller?.stop()
        controller = null
        awaitingTracks = false
    }
}

/** State after auto-sync found [offsetMs] (its `Locked` status arrives through `onStatus`). */
internal fun PlayerUiState.withDetectedOffset(offsetMs: Long): PlayerUiState =
    copy(subtitleOffsetMs = SubtitleOffset.clamp(offsetMs))

/** State after a manual `−`/`+` press: steps from whatever is shown (auto or not); auto-sync is now out. */
internal fun PlayerUiState.withManualStep(direction: Int): PlayerUiState =
    copy(subtitleOffsetMs = SubtitleOffset.step(subtitleOffsetMs, direction), subtitleSyncStatus = AutoSyncStatus.Manual)

/** Starts detection for the active external track when the per-profile setting allows it. */
internal fun PlayerViewModel.startAutoSync(timeline: ExternalSubtitleTimeline) {
    autoSync.stop()
    val state = _state.value
    val option = state.selectedSubtitle ?: return
    if (!settings.subtitles.autoSyncExternal || option.source != SubtitleSource.EXTERNAL || autoSync.manualOverride) return
    val manager = currentManager() ?: return
    if (manager.player.currentTracks.isEmpty) {
        autoSync.awaitingTracks = true
        return
    }
    val reference = ReferenceTrackPicker.pick(option.language, state.subtitleOptions)
    autoSync.controller = SubtitleAutoSync(
        viewModelScope,
        manager,
        onDetected = { applyDetectedOffset(it) },
        onStatus = { status -> _state.update { it.copy(subtitleSyncStatus = status) } },
    ).also { it.start(timeline.cues, reference?.id) }
}

/** Tracks changed: starts a detection that was waiting for them. */
internal fun PlayerViewModel.onTracksForAutoSync() {
    if (autoSync.awaitingTracks) subtitles.activeTimeline?.let { startAutoSync(it) }
}

/**
 * A new stream of the same title starts (fallback, stream switch): another release can be timed
 * differently, so an auto-detected offset is dropped and detected again; a manual offset is kept.
 */
internal fun PlayerViewModel.restartAutoSyncForNewStream() {
    autoSync.stop()
    if (!_state.value.externalSubtitleActive || autoSync.manualOverride) return
    _state.update { it.copy(subtitleOffsetMs = 0, subtitleSyncStatus = AutoSyncStatus.Off) }
    autoSync.awaitingTracks = settings.subtitles.autoSyncExternal
}

private fun PlayerViewModel.applyDetectedOffset(offsetMs: Long) {
    _state.update { it.withDetectedOffset(offsetMs) }
    currentManager()?.let { refreshCue(it) }
}

/** Recomputes the cue on screen now (offset changes must not wait for the next tick). */
internal fun PlayerViewModel.refreshCue(manager: SeaStreamsPlayerManager) {
    val state = _state.value
    _cue.value = subtitles.textAt(manager.player.currentPosition, state.subtitleOffsetMs, state.externalSubtitleActive, manager.embeddedCues.value)
        ?.takeIf { state.selectedSubtitleId != null }
}
