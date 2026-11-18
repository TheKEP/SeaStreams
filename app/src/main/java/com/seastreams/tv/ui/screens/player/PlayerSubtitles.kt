package com.seastreams.tv.ui.screens.player

import com.seastreams.tv.core.util.orNullUnlessCancelled
import com.seastreams.tv.domain.model.SubtitleTrack
import com.seastreams.tv.domain.playback.SubtitleOption
import com.seastreams.tv.domain.playback.SubtitleSource
import com.seastreams.tv.domain.playback.TrackOrdering
import com.seastreams.tv.player.subtitle.CleanSdhTextRenderer
import com.seastreams.tv.player.subtitle.ExternalSubtitleLoader
import com.seastreams.tv.player.subtitle.ExternalSubtitleTimeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The subtitle half of a player session: the merged embedded + addon track list (preferred language
 * first, non-preferred optionally hidden), the active addon track's parsed timeline, and the cue text
 * to draw at a position (through [CleanSdhTextRenderer]).
 */
class PlayerSubtitles(private val scope: CoroutineScope, private val loader: suspend () -> ExternalSubtitleLoader) {

    val renderer = CleanSdhTextRenderer()
    private var timeline: ExternalSubtitleTimeline? = null
    private var loadJob: Job? = null
    private var external: List<SubtitleOption> = emptyList()

    /** Addon subtitles of the playing title, as options keyed by URL. */
    fun setExternal(tracks: List<SubtitleTrack>) {
        external = tracks.map { track ->
            val language = track.language.ifBlank { null }
            SubtitleOption(
                id = track.url,
                label = listOfNotNull(com.seastreams.tv.player.PlayerTracks.languageName(language) ?: language, track.addonName).joinToString(" · "),
                language = language,
                source = SubtitleSource.EXTERNAL,
            )
        }
    }

    /** Every option to list: embedded tracks before addon tracks, then preferred language first. */
    fun options(embedded: List<SubtitleOption>, preferred: String, hideNonPreferred: Boolean): List<SubtitleOption> =
        TrackOrdering.subtitles(embedded + external, preferred, hideNonPreferred)

    /**
     * Switches the addon timeline: loads [option]'s file when it is external, or drops the timeline.
     * [onFailed] runs when the file can't be downloaded or parsed.
     */
    fun activate(option: SubtitleOption?, onFailed: () -> Unit) {
        loadJob?.cancel()
        timeline = null
        if (option?.source != SubtitleSource.EXTERNAL) return
        loadJob = scope.launch {
            val loaded = orNullUnlessCancelled { loader().load(option.id) }
            if (loaded == null) onFailed() else timeline = loaded
        }
    }

    /** Cue text at [positionMs] shifted by the sync [offsetMs]; embedded [cues] when no addon track is active. */
    fun textAt(positionMs: Long, offsetMs: Long, externalActive: Boolean, cues: List<CharSequence?>): String? {
        if (!externalActive) return renderer.render(cues)
        val active = timeline ?: return null
        return renderer.render(active.textsAt((positionMs + offsetMs) * MICROS_PER_MILLI))
    }

    fun clear() {
        loadJob?.cancel()
        timeline = null
        external = emptyList()
    }

    private companion object {
        const val MICROS_PER_MILLI = 1_000L
    }
}
