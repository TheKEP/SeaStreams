package com.seastreams.tv.ui.screens.player

import androidx.lifecycle.viewModelScope
import com.seastreams.tv.domain.playback.SubtitleSource
import com.seastreams.tv.player.SeaStreamsPlayerManager
import com.seastreams.tv.player.subtitle.ExternalSubtitleTimeline
import com.seastreams.tv.player.subtitle.SubtitleOffset
import com.seastreams.tv.player.subtitle.sync.AutoSyncStatus
import com.seastreams.tv.player.subtitle.sync.ReferenceTrackPicker
import com.seastreams.tv.player.subtitle.sync.SubtitleAutoSync
import com.seastreams.tv.player.subtitle.sync.SyncLock
import com.seastreams.tv.player.subtitle.sync.SyncRate
import com.seastreams.tv.player.subtitle.sync.SyncSource
import kotlinx.coroutines.flow.update

/*
 * External subtitle auto-sync for the player session (PRD §9). Detection runs in [SubtitleAutoSync];
 * this file decides when it runs, feeds the result into the HUD's `- 0.0s +` offset (and the file's
 * clock rate), and remembers results per stream + track for the session.
 */

/**
 * A sync remembered for one stream + external track this session. [manual]: the user's own offset
 * after a nudge (rate kept), which always wins over auto-sync when the track is picked again.
 */
internal data class CachedSync(val offsetMs: Long, val rate: SyncRate, val source: SyncSource?, val manual: Boolean)

/** Auto-sync bookkeeping of one session. */
internal class AutoSyncSession {
    var controller: SubtitleAutoSync? = null

    /** The user nudged the offset for this track: auto-sync stays off until another track is picked. */
    var manualOverride = false

    /** Start once the current stream's tracks are known (needed to find an embedded reference). */
    var awaitingTracks = false

    /** Results by [syncKey], so picking a track again applies at once. Session-only. */
    private val results = LinkedHashMap<String, CachedSync>()

    /** What was remembered for [state]'s stream + external track, if anything. */
    fun cached(state: PlayerUiState): CachedSync? = syncKey(state)?.let { results[it] }

    /**
     * Remembers [state]'s shown auto result (final lock, tracking re-centre). Never after a manual
     * nudge of this track, and never over a remembered manual value.
     */
    fun rememberAuto(state: PlayerUiState, source: SyncSource) {
        if (manualOverride) return
        val key = syncKey(state) ?: return
        if (results[key]?.manual == true) return
        put(key, CachedSync(state.subtitleOffsetMs, state.subtitleRate, source, manual = false))
    }

    /**
     * The user nudged: remembers *their* offset and the rate in use, so picking the track again
     * restores it instead of the pre-nudge auto value. Later nudges update it.
     */
    fun rememberManual(state: PlayerUiState) {
        val key = syncKey(state) ?: return
        put(key, CachedSync(state.subtitleOffsetMs, state.subtitleRate, results[key]?.source, manual = true))
    }

    private fun put(key: String, sync: CachedSync) {
        results.remove(key)
        results[key] = sync
        if (results.size > MAX_RESULTS) results.remove(results.keys.first())
    }

    fun stop() {
        controller?.stop()
        controller = null
        awaitingTracks = false
    }

    /** Session end: stops detection and forgets every remembered result. */
    fun clear() {
        stop()
        manualOverride = false
        results.clear()
    }

    private companion object {
        const val MAX_RESULTS = 32
    }
}

/** The playing stream + active external track, or `null` when there's no external track. */
internal fun syncKey(state: PlayerUiState): String? {
    val option = state.selectedSubtitle?.takeIf { it.source == SubtitleSource.EXTERNAL } ?: return null
    val stream = state.streams.getOrNull(state.streamIndex)?.url ?: return null
    return "$stream\n${option.id}"
}

/** State after auto-sync found [offsetMs] on [rate]'s clock (its `Locked` status arrives through `onStatus`). */
internal fun PlayerUiState.withDetectedOffset(offsetMs: Long, rate: SyncRate = SyncRate.UNIT): PlayerUiState =
    copy(subtitleOffsetMs = SubtitleOffset.clamp(offsetMs), subtitleRate = rate)

/** State restoring the user's remembered manual offset and rate for a re-picked track. */
internal fun PlayerUiState.withManualSync(cached: CachedSync): PlayerUiState =
    copy(subtitleOffsetMs = cached.offsetMs, subtitleRate = cached.rate, subtitleSyncStatus = AutoSyncStatus.Manual)

/** State after a manual `−`/`+` press: steps from whatever is shown (auto or not), keeps the rate; auto-sync is now out. */
internal fun PlayerUiState.withManualStep(direction: Int): PlayerUiState =
    copy(subtitleOffsetMs = SubtitleOffset.step(subtitleOffsetMs, direction), subtitleSyncStatus = AutoSyncStatus.Manual)

/**
 * Starts detection for the active external track when the per-profile setting allows it. A result
 * remembered for this stream + track is applied at once: the user's own (manual) offset with no
 * detection at all, an auto result by resuming straight into drift tracking.
 */
internal fun PlayerViewModel.startAutoSync(timeline: ExternalSubtitleTimeline) {
    autoSync.stop()
    val state = _state.value
    val option = state.selectedSubtitle ?: return
    if (option.source != SubtitleSource.EXTERNAL) return
    val cached = autoSync.cached(state)
    if (cached?.manual == true) {
        restoreManualSync(cached)
        return
    }
    if (!settings.subtitles.autoSyncExternal || autoSync.manualOverride) return
    val manager = currentManager() ?: return
    if (manager.player.currentTracks.isEmpty) {
        autoSync.awaitingTracks = true
        return
    }
    val reference = ReferenceTrackPicker.pick(option.language, state.subtitleOptions)
    val resume = cached?.let { SyncLock(it.offsetMs, it.rate, it.source ?: SyncSource.AUDIO) }
    autoSync.controller = SubtitleAutoSync(
        viewModelScope,
        manager,
        onDetected = { applyDetectedSync(it) },
        onStatus = { status ->
            _state.update { it.copy(subtitleSyncStatus = status) }
            if (status is AutoSyncStatus.Locked && status.tracking) autoSync.rememberAuto(_state.value, status.source)
        },
    ).also { it.start(timeline.cues, reference?.option?.id, reference?.otherLanguage, resume) }
}

/** Tracks changed: starts a detection that was waiting for them. */
internal fun PlayerViewModel.onTracksForAutoSync() {
    if (autoSync.awaitingTracks) subtitles.activeTimeline?.let { startAutoSync(it) }
}

/**
 * A new stream of the same title starts (fallback, stream switch): another release can be timed
 * differently, so an auto-detected offset and rate are dropped and detected again; a manual one is kept.
 */
internal fun PlayerViewModel.restartAutoSyncForNewStream() {
    autoSync.stop()
    if (!_state.value.externalSubtitleActive || autoSync.manualOverride) return
    _state.update { it.copy(subtitleOffsetMs = 0, subtitleRate = SyncRate.UNIT, subtitleSyncStatus = AutoSyncStatus.Off) }
    autoSync.awaitingTracks = settings.subtitles.autoSyncExternal
}

private fun PlayerViewModel.restoreManualSync(cached: CachedSync) {
    autoSync.manualOverride = true
    _state.update { it.withManualSync(cached) }
    currentManager()?.let { refreshCue(it) }
}

private fun PlayerViewModel.applyDetectedSync(lock: SyncLock) {
    _state.update { it.withDetectedOffset(lock.offsetMs, lock.rate) }
    val state = _state.value
    if ((state.subtitleSyncStatus as? AutoSyncStatus.Locked)?.tracking == true) autoSync.rememberAuto(state, lock.source)
    currentManager()?.let { refreshCue(it) }
}

/** Recomputes the cue on screen now (offset changes must not wait for the next tick). */
internal fun PlayerViewModel.refreshCue(manager: SeaStreamsPlayerManager) {
    val state = _state.value
    _cue.value = subtitles.textAt(
        manager.player.currentPosition, state.subtitleOffsetMs, state.externalSubtitleActive, manager.embeddedCues.value, state.subtitleRate,
    )?.takeIf { state.selectedSubtitleId != null }
}
