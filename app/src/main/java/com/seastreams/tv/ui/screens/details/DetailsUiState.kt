package com.seastreams.tv.ui.screens.details

import com.seastreams.tv.core.util.MILLIS_PER_MINUTE
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
    /** `true` once the progress collector has emitted for this title (even an empty list). */
    val progressLoaded: Boolean = false,
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

    /** Movie Details only: `Clear progress` is offered exactly while `Play` reads `Resume` (PRD §6). */
    val canClearProgress: Boolean
        get() = !isShow && progress.firstOrNull { it.episode == null }?.inProgress == true

    /** `42m left` under the synopsis when the movie or next-up episode is part-watched. */
    val resumeNote: String?
        get() {
            val episode = nextUpEpisode
            val point = progress.firstOrNull { it.episode == episode && it.inProgress } ?: return null
            val minutesLeft = ((point.durationMs - point.positionMs) / MILLIS_PER_MINUTE).toInt()
            return MetadataFormat.runtime(minutesLeft)?.let { "$it left" }
        }

    /**
     * The resume line to render. Movies and shows always get one (blank without progress), so the note
     * arriving or being cleared never shifts the action stack (PRD §6).
     */
    val resumeNoteSlot: String get() = resumeNote.orEmpty()

    /**
     * The stack's labels are final: saved progress has arrived and, for shows, the seasons that pick the
     * next-up episode. Until then the stack is laid out and focusable but not drawn, so `Play` never
     * flashes before `Resume S1:E5`. Error stacks are always ready (a missing profile never loads progress).
     */
    val actionsReady: Boolean
        get() = content == DetailsContent.Failed || content == DetailsContent.Restricted ||
            (progressLoaded && (!isShow || details != null))

    val libraryLabel: String get() = libraryButtonLabel(inLibrary)
}

/** PRD §6: saved titles offer removal, everything else offers adding. */
fun libraryButtonLabel(inLibrary: Boolean): String = if (inLibrary) "Remove from library" else "Add to library"
