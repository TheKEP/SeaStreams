package com.seastreams.tv.ui.screens.player

import androidx.media3.common.Player
import com.seastreams.tv.player.knownOrZero
import kotlinx.coroutines.flow.update

// Play/pause and seeking for the open session (PRD §9), kept apart from PlayerViewModel's session logic.

fun PlayerViewModel.togglePlayPause() {
    val player = currentManager()?.player ?: return
    if (player.isPlaying) pauseAndSave() else resume(player)
}

/** `Enter` while playing: pause and open the HUD (PRD §9). */
fun PlayerViewModel.pauseAndShowHud() {
    pauseAndSave()
    showHud()
}

fun PlayerViewModel.seekBy(deltaMs: Long) {
    val player = currentManager()?.player ?: return
    seekTo(player.currentPosition + deltaMs)
}

fun PlayerViewModel.seekTo(positionMs: Long) {
    val player = currentManager()?.player ?: return
    val duration = player.duration.knownOrZero()
    player.seekTo(if (duration > 0) positionMs.coerceIn(0, duration) else positionMs.coerceAtLeast(0))
    _progress.update { it.copy(positionMs = player.currentPosition) }
}

/** The app went to the background: pause and keep the position. */
fun PlayerViewModel.onHostPaused() = pauseAndSave()

private fun resume(player: Player) {
    if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
    player.play()
}

private fun PlayerViewModel.pauseAndSave() {
    val player = currentManager()?.player ?: return
    player.pause()
    saveNow()
}
