package com.seastreams.tv.ui.screens.details

import androidx.compose.ui.focus.FocusRequester
import com.seastreams.tv.domain.model.Episode
import com.seastreams.tv.domain.model.SeasonInfo
import com.seastreams.tv.ui.components.ActionMenuRequest
import com.seastreams.tv.ui.components.MenuAction
import com.seastreams.tv.ui.components.MetadataFormat

/** Season pill long-press menu (PRD §6): mark the whole season, or shuffle within it. */
fun seasonMenu(
    season: SeasonInfo,
    returnFocus: FocusRequester,
    onMark: (watched: Boolean) -> Unit,
    onShuffle: () -> Unit,
): ActionMenuRequest = ActionMenuRequest(
    title = season.displayName(),
    subtitle = "${season.episodeCount} episodes",
    actions = listOf(
        MenuAction(MARK_SEASON_WATCHED) { onMark(true) },
        MenuAction(MARK_SEASON_UNWATCHED) { onMark(false) },
        MenuAction(SHUFFLE_SEASON, onShuffle),
    ),
    returnFocus = returnFocus,
)

/**
 * Episode row long-press menu (PRD §6). The single-episode toggle reads `Mark unwatched` when
 * [watched], else `Mark watched`.
 */
fun episodeMenu(
    episode: Episode,
    watched: Boolean,
    returnFocus: FocusRequester,
    onViewStreams: () -> Unit,
    onAutoPlay: () -> Unit,
    onToggleWatched: () -> Unit,
    onMarkPrevious: (watched: Boolean) -> Unit,
): ActionMenuRequest = ActionMenuRequest(
    title = episode.title,
    subtitle = MetadataFormat.episodeBadge(episode.ref.season, episode.ref.episode) ?: "Episode ${episode.ref.episode}",
    actions = listOf(
        MenuAction(VIEW_STREAMS, onViewStreams),
        MenuAction(AUTO_PLAY, onAutoPlay),
        MenuAction(if (watched) MARK_UNWATCHED else MARK_WATCHED, onToggleWatched),
        MenuAction(MARK_PREVIOUS_WATCHED) { onMarkPrevious(true) },
        MenuAction(MARK_PREVIOUS_UNWATCHED) { onMarkPrevious(false) },
    ),
    returnFocus = returnFocus,
)

internal fun SeasonInfo.displayName(): String = if (number == 0) "Specials" else "Season $number"

const val MARK_SEASON_WATCHED = "Mark Season Watched"
const val MARK_SEASON_UNWATCHED = "Mark Season Unwatched"
const val SHUFFLE_SEASON = "Shuffle Season"
const val VIEW_STREAMS = "View streams"
const val AUTO_PLAY = "Auto-play"
const val MARK_WATCHED = "Mark watched"
const val MARK_UNWATCHED = "Mark unwatched"
const val MARK_PREVIOUS_WATCHED = "Mark previous in season watched"
const val MARK_PREVIOUS_UNWATCHED = "Mark previous in season unwatched"
