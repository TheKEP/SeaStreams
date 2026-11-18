package com.seastreams.tv.ui.screens.playback

import com.seastreams.tv.domain.playback.ResolvedPlayback
import com.seastreams.tv.ui.components.MetadataFormat

/**
 * `No streams found` / `No streams found for S1:E2`, plus the addons that didn't respond. Shared by
 * the picker/auto-play path and the open player's episode switching.
 */
fun noStreamsMessage(playback: ResolvedPlayback): String {
    val ref = playback.target.episode
    val badge = ref?.let { MetadataFormat.episodeBadge(it.season, it.episode) }
    val failed = playback.resolution.failedAddons
    val base = if (badge == null) "No streams found" else "No streams found for $badge"
    return if (failed.isEmpty()) base else "$base (${failed.joinToString()} didn't respond)"
}
