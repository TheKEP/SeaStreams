package com.seastreams.tv.ui.screens.details

import com.seastreams.tv.domain.model.EpisodeRef
import com.seastreams.tv.domain.model.MediaItem
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.TitleDetails
import com.seastreams.tv.domain.playback.EpisodeNavigator
import com.seastreams.tv.domain.playback.ProgressPoint
import com.seastreams.tv.ui.components.MetadataFormat

sealed interface DetailsContent {
    data object Loading : DetailsContent

    data class Loaded(val details: TitleDetails) : DetailsContent

    /** Outside the active Kids profile's age ceiling. */
    data object Restricted : DetailsContent

    data object Failed : DetailsContent
}

data class DetailsUiState(
    val profileId: Long? = null,
    /** The card's item at first, replaced by the full TMDB record once loaded. */
    val media: MediaItem? = null,
    val content: DetailsContent = DetailsContent.Loading,
    /** `true` once the IMDb lookup has finished (successfully or not). */
    val imdbResolved: Boolean = false,
    val imdbId: String? = null,
    val inLibrary: Boolean = false,
    val progress: List<ProgressPoint> = emptyList(),
) {
    val details: TitleDetails? get() = (content as? DetailsContent.Loaded)?.details

    val isShow: Boolean get() = media?.mediaType == MediaType.TV

    /** The episode `Play` starts for a show; `null` for movies or before seasons are known. */
    val nextUpEpisode: EpisodeRef?
        get() {
            val details = details ?: return null
            if (!isShow) return null
            return EpisodeNavigator.nextUp(progress, details.seasons, details.lastAired)
        }

    /** `Play`, `Resume`, or for shows `Play S1:E1` / `Resume S2:E4`. */
    val playLabel: String
        get() {
            val episode = nextUpEpisode
            val point = progress.firstOrNull { it.episode == episode }
            val verb = if (point?.inProgress == true) "Resume" else "Play"
            val badge = episode?.let { MetadataFormat.episodeBadge(it.season, it.episode) }
            return if (badge == null) verb else "$verb $badge"
        }

    val libraryLabel: String get() = libraryButtonLabel(inLibrary)
}

/** PRD §6: saved titles offer removal, everything else offers adding. */
fun libraryButtonLabel(inLibrary: Boolean): String = if (inLibrary) "Remove from library" else "Add to library"
