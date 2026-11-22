package com.seastreams.tv.ui.screens.player

import com.seastreams.tv.core.util.orNullUnlessCancelled
import com.seastreams.tv.domain.model.SubtitleTrack
import com.seastreams.tv.domain.playback.SubtitleOption
import com.seastreams.tv.domain.playback.SubtitleSource
import com.seastreams.tv.domain.playback.TrackOrdering
import com.seastreams.tv.player.subtitle.CleanSdhTextRenderer
import com.seastreams.tv.player.subtitle.ExternalSubtitleLoader
import com.seastreams.tv.player.subtitle.ExternalSubtitleTimeline
import com.seastreams.tv.player.subtitle.sync.ReferenceTrackPicker
import com.seastreams.tv.player.subtitle.sync.SyncRate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The subtitle choice made for the playing title ([OFF] for an explicit Off), re-applied when another
 * stream of the same title loads (fallback, stream switch): embedded track ids differ per stream, so a
 * choice is matched by source and language, preferring the same label.
 */
data class SubtitleChoice(val source: SubtitleSource?, val language: String?, val label: String?) {

    val isOff: Boolean get() = source == null

    /** The option on the new stream matching this choice, or `null` (none, or this is [OFF]). */
    fun match(options: List<SubtitleOption>): SubtitleOption? {
        if (isOff) return null
        options.firstOrNull { it.source == source && it.label == label && sameLanguage(it) }?.let { return it }
        return options.firstOrNull { it.source == source && sameLanguage(it) }
    }

    private fun sameLanguage(option: SubtitleOption): Boolean =
        ReferenceTrackPicker.normalize(option.language) == ReferenceTrackPicker.normalize(language)

    companion object {
        val OFF = SubtitleChoice(null, null, null)

        fun of(option: SubtitleOption?): SubtitleChoice = option?.let { SubtitleChoice(it.source, it.language, it.label) } ?: OFF
    }
}

/**
 * The subtitle half of a player session: the merged embedded + addon track list (preferred language
 * first, non-preferred optionally hidden), the active addon track's parsed timeline, and the cue text
 * to draw at a position (through [CleanSdhTextRenderer]).
 */
class PlayerSubtitles(private val scope: CoroutineScope, private val loader: suspend () -> ExternalSubtitleLoader) {

    val renderer = CleanSdhTextRenderer()
    private var timeline: ExternalSubtitleTimeline? = null

    /** The parsed file of the active addon track, once loaded. */
    val activeTimeline: ExternalSubtitleTimeline? get() = timeline
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
     * [onFailed] runs when the file can't be downloaded or parsed, [onLoaded] once it is ready.
     */
    fun activate(option: SubtitleOption?, onLoaded: (ExternalSubtitleTimeline) -> Unit = {}, onFailed: () -> Unit) {
        loadJob?.cancel()
        timeline = null
        if (option?.source != SubtitleSource.EXTERNAL) return
        loadJob = scope.launch {
            val loaded = orNullUnlessCancelled { loader().load(option.id) }
            if (loaded == null) {
                onFailed()
            } else {
                timeline = loaded
                onLoaded(loaded)
            }
        }
    }

    /** Cue text at [positionMs] shifted by the sync [offsetMs]; embedded [cues] when no addon track is active. */
    /**
     * The cue text at [positionMs]: an active addon track's cues at `(position + offset)` moved onto the
     * file's own clock by [rate] (auto-sync's frame-rate fit), else the embedded [cues].
     */
    fun textAt(positionMs: Long, offsetMs: Long, externalActive: Boolean, cues: List<CharSequence?>, rate: SyncRate = SyncRate.UNIT): String? {
        if (!externalActive) return renderer.render(cues)
        val active = timeline ?: return null
        return renderer.render(active.textsAt(rate.toSubtitleUs((positionMs + offsetMs) * MICROS_PER_MILLI)))
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
