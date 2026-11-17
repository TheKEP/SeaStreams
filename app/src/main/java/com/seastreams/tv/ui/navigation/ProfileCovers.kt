package com.seastreams.tv.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.focus.FocusRequester
import com.seastreams.tv.domain.model.MediaItem
import com.seastreams.tv.domain.model.TitleDetails
import com.seastreams.tv.ui.screens.details.DetailsScreen
import com.seastreams.tv.ui.screens.details.EpisodesScreen
import com.seastreams.tv.ui.screens.playback.PlaybackViewModel
import com.seastreams.tv.ui.screens.settings.SettingsScreen

/** Full-screen layers drawn over the current page while it stays composed underneath. */
sealed interface Cover {
    /** [autoPlay]: opened from Continue Watching with auto-play on, so playback starts at once. */
    data class Details(val item: MediaItem, val autoPlay: Boolean) : Cover

    data class Episodes(val details: TitleDetails, val imdbId: String?) : Cover

    data object Settings : Cover
}

/** One open [Cover]; [origin] is the card/button that opened it and regains focus when it closes. */
data class CoverEntry(val cover: Cover, val origin: FocusRequester?, val id: Long)

@Composable
fun CoverContent(
    entry: CoverEntry,
    profileId: Long,
    playback: PlaybackViewModel,
    onPush: (Cover) -> Unit,
    onUpdate: (id: Long, Cover) -> Unit,
    onClose: () -> Unit,
) {
    when (val cover = entry.cover) {
        is Cover.Details -> DetailsScreen(
            profileId = profileId,
            item = cover.item,
            autoPlay = cover.autoPlay,
            viewModel = activityViewModel(),
            playback = playback,
            onOpenEpisodes = { details, imdbId -> onPush(Cover.Episodes(details, imdbId)) },
            onAutoPlayHandled = { onUpdate(entry.id, cover.copy(autoPlay = false)) },
            onBack = onClose,
        )
        is Cover.Episodes -> EpisodesScreen(
            profileId = profileId,
            details = cover.details,
            imdbId = cover.imdbId,
            viewModel = activityViewModel(),
            playback = playback,
        )
        Cover.Settings -> SettingsScreen(
            profileId = profileId,
            viewModel = activityViewModel(),
            appearance = activityViewModel(),
        )
    }
}
