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
    data class Details(val item: MediaItem) : Cover

    data class Episodes(val details: TitleDetails, val imdbId: String?) : Cover

    data object Settings : Cover

    /** The profile switcher opened from `Settings → Home → Profiles` (PRD §2 *Opened from Settings*). */
    data object Profiles : Cover
}

/** One open [Cover]; [origin] is the card/button that opened it and regains focus when it closes. */
data class CoverEntry(val cover: Cover, val origin: FocusRequester?, val id: Long)

/** The cover stack after a pop, plus the control that opened the popped cover (to refocus), if any. */
data class CoverPop(val remaining: List<CoverEntry>, val refocus: FocusRequester?)

/**
 * Pure push/pop rules of ProfileShell's cover stack. A push records the focus origin marked just
 * before it (the gear for Settings, the `Profiles` row for the profile switcher); a pop removes the
 * top cover and hands back that origin, so `Back` lands exactly on the control that opened it.
 */
object CoverStack {
    fun push(covers: List<CoverEntry>, cover: Cover, origin: FocusRequester?, id: Long): List<CoverEntry> =
        covers + CoverEntry(cover, origin, id)

    /** `null` when nothing is open. */
    fun pop(covers: List<CoverEntry>): CoverPop? {
        val top = covers.lastOrNull() ?: return null
        return CoverPop(covers.dropLast(1), top.origin)
    }
}

@Composable
fun CoverContent(
    entry: CoverEntry,
    profileId: Long,
    playback: PlaybackViewModel,
    onPush: (Cover) -> Unit,
    onClose: () -> Unit,
    onEnterProfile: (Long) -> Unit,
) {
    when (val cover = entry.cover) {
        is Cover.Details -> DetailsScreen(
            profileId = profileId,
            item = cover.item,
            viewModel = activityViewModel(),
            playback = playback,
            onOpenEpisodes = { details, imdbId -> onPush(Cover.Episodes(details, imdbId)) },
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
            sources = activityViewModel(),
            debrid = activityViewModel(),
            streams = activityViewModel(),
            onOpenProfiles = { onPush(Cover.Profiles) },
        )
        // Back is the shell's cover BackHandler (→ Settings, `Profiles` row focused); a pick enters Home.
        Cover.Profiles -> ProfileSwitcher(onProfileSelected = onEnterProfile)
    }
}
