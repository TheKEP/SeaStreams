package com.seastreams.tv.ui.screens.player

import androidx.lifecycle.viewModelScope
import com.seastreams.tv.core.datastore.SubtitleStyle
import com.seastreams.tv.domain.playback.SubtitleOption
import com.seastreams.tv.domain.playback.SubtitleSource
import com.seastreams.tv.domain.playback.TrackOrdering
import com.seastreams.tv.player.PlayerTracks
import com.seastreams.tv.player.SeaStreamsPlayerManager
import com.seastreams.tv.player.subtitle.sync.AutoSyncStatus
import com.seastreams.tv.player.subtitle.sync.SyncRate
import com.seastreams.tv.domain.playback.PlayerProgressTracker
import com.seastreams.tv.player.knownOrZero
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * Session-only subtitle and audio choices made in the player's overlays (PRD §9). None of them is
 * written to SettingsDataStore: the next playback starts from General Settings again.
 */

/** Re-reads ExoPlayer's tracks; the first time tracks appear, applies the *Always On* subtitle default. */
internal fun PlayerViewModel.refreshTracks(manager: SeaStreamsPlayerManager) {
    val tracks = manager.player.currentTracks
    val prefs = settings.subtitles
    val options = subtitles.options(PlayerTracks.embeddedSubtitles(tracks), prefs.preferredLanguage, prefs.hideNonPreferredLanguages)
    val audio = TrackOrdering.audio(PlayerTracks.audio(tracks), settings.audio.preferredAudioLanguage)
    _state.update {
        it.copy(subtitleOptions = options, audioOptions = audio, selectedAudioId = PlayerTracks.selectedAudioId(tracks))
    }
    if (!initialSubtitleDone && !tracks.isEmpty) {
        initialSubtitleDone = true
        // A choice already made for this title (before a stream switch) wins over the Always On/Off default.
        val remembered = subtitleChoice
        val pick = when {
            remembered == null -> TrackOrdering.initialSubtitle(options, prefs.mode, prefs.preferredLanguage)
            remembered.isOff -> null
            else -> remembered.match(options) ?: TrackOrdering.initialSubtitle(options, prefs.mode, prefs.preferredLanguage)
        }
        pick?.let { selectSubtitle(it) }
    }
    onTracksForAutoSync()
}

fun PlayerViewModel.selectSubtitle(option: SubtitleOption?) {
    val manager = currentManager() ?: return
    // Auto-sync's background reference track goes first, so it can't undo the embedded choice below.
    autoSync.stop()
    autoSync.manualOverride = false
    subtitleChoice = SubtitleChoice.of(option)
    _state.update { it.copy(selectedSubtitleId = option?.id, subtitleOffsetMs = 0, subtitleRate = SyncRate.UNIT, subtitleSyncStatus = AutoSyncStatus.Off) }
    PlayerTracks.selectEmbeddedText(manager.player, option?.takeIf { it.source == SubtitleSource.EMBEDDED }?.id)
    subtitles.activate(option, onLoaded = { startAutoSync(it) }) {
        _state.update { it.copy(selectedSubtitleId = null) }
        viewModelScope.launch { _messages.send("Couldn't load those subtitles") }
    }
}

/**
 * HUD `−`/`+`: a manual nudge always wins over auto-sync for the rest of this track, and is remembered
 * for it (detection is stopped first, so no auto result can land after the nudge).
 */
fun PlayerViewModel.stepSubtitleOffset(direction: Int) {
    autoSync.stop()
    autoSync.manualOverride = true
    _state.update { it.withManualStep(direction) }
    autoSync.rememberManual(_state.value)
    currentManager()?.let { refreshCue(it) }
}

fun PlayerViewModel.setCleanSdh(enabled: Boolean) {
    subtitles.renderer.cleanSdh = enabled
    _state.update { it.copy(cleanSdh = enabled) }
}

fun PlayerViewModel.updateSubtitleStyle(transform: (SubtitleStyle) -> SubtitleStyle) =
    _state.update { it.copy(subtitleStyle = transform(it.subtitleStyle)) }

fun PlayerViewModel.selectAudio(id: String) {
    val manager = currentManager() ?: return
    PlayerTracks.selectAudio(manager.player, id)
    _state.update { it.copy(selectedAudioId = id) }
}

/** Session audio change; turning an effect on also turns passthrough off for this video. */
fun PlayerViewModel.updateSessionAudio(transform: (SessionAudio) -> SessionAudio) {
    val previous = _state.value.audio
    val updated = transform(previous).withPassthroughAutoOff(previous)
    _state.update { it.copy(audio = updated) }
    val manager = currentManager() ?: return
    manager.setDsp(updated.toDsp())
    manager.setPassthrough(updated.passthrough)
}

/** Twice a second: position, progress saves and the Info overlay; cue text every 100 ms. */
internal suspend fun PlayerViewModel.tick(manager: SeaStreamsPlayerManager) {
    var ticks = 0
    while (true) {
        val player = manager.player
        val state = _state.value
        val position = player.currentPosition
        refreshCue(manager)
        if (ticks % PROGRESS_EVERY_TICKS == 0) {
            _progress.value = PlayerProgress(position, player.duration.knownOrZero(), player.bufferedPosition)
            val request = state.request
            if (request != null && player.isPlaying && kotlin.math.abs(position - lastSaveAtPosition) >= PlayerProgressTracker.SAVE_INTERVAL_MS) {
                lastSaveAtPosition = position
                progressTracker.save(request, position, player.duration.knownOrZero())
            }
        }
        if (state.panel == PlayerPanel.INFO && ticks % INFO_EVERY_TICKS == 0) _info.value = buildInfoRows(state, manager)
        ticks++
        delay(TICK_MS)
    }
}

private const val TICK_MS = 100L
private const val PROGRESS_EVERY_TICKS = 5
private const val INFO_EVERY_TICKS = 10
