package com.seastreams.tv.ui.screens.playback

import com.seastreams.tv.domain.model.ContinueWatchingItem
import com.seastreams.tv.domain.model.EpisodeRef
import com.seastreams.tv.domain.model.PlaybackArtwork
import com.seastreams.tv.domain.model.playbackArtwork
import com.seastreams.tv.domain.playback.PlaybackTarget
import com.seastreams.tv.ui.components.MetadataFormat

/** The stream list's title line. */
internal fun PlaybackTarget.title(): String = details.media.title

/**
 * A Continue Watching card's artwork before its details load. Stored progress rows carry no logo, so
 * without one only the backdrop shows (blank title) until the details' logo arrives — never title
 * text that then swaps to a logo.
 */
internal fun cardArtwork(item: ContinueWatchingItem): PlaybackArtwork {
    val artwork = item.media.playbackArtwork()
    return if (artwork.logoUrl != null) artwork else artwork.copy(title = "")
}

/** `S1:E2 · Pilot` for episodes, `null` for movies. */
internal fun PlaybackTarget.subtitle(): String? {
    val ref: EpisodeRef = episode ?: return null
    val badge = MetadataFormat.episodeBadge(ref.season, ref.episode) ?: return null
    return listOfNotNull(badge, episodeTitle).joinToString(" · ")
}
