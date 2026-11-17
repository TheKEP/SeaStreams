package com.seastreams.tv.ui.screens.playback

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.seastreams.tv.core.datastore.AppSettings
import com.seastreams.tv.core.datastore.PlayerChoice
import com.seastreams.tv.core.datastore.SettingsDataStore
import com.seastreams.tv.core.datastore.SubtitleMode
import com.seastreams.tv.core.util.orNullUnlessCancelled
import com.seastreams.tv.data.details.TitleDetailsRepository
import com.seastreams.tv.domain.model.EpisodeRef
import com.seastreams.tv.domain.model.StreamSource
import com.seastreams.tv.domain.model.SubtitleTrack
import com.seastreams.tv.domain.playback.EpisodeNavigator
import com.seastreams.tv.domain.playback.ExternalPlaybackLaunch
import com.seastreams.tv.domain.playback.ExternalPlaybackProgress
import com.seastreams.tv.domain.playback.ExternalPlayerExtras
import com.seastreams.tv.domain.playback.PlaybackProgressRecorder
import com.seastreams.tv.domain.playback.PlaybackTarget
import com.seastreams.tv.domain.usecase.ResolveStreamsUseCase
import com.seastreams.tv.domain.usecase.StreamQuery
import com.seastreams.tv.domain.usecase.StreamResolution
import com.seastreams.tv.domain.usecase.subtitleLanguageMatches
import com.seastreams.tv.domain.usecase.topStream
import com.seastreams.tv.player.external.ExternalLaunchSpec
import com.seastreams.tv.ui.components.MetadataFormat
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import javax.inject.Inject

/** The stream list modal. [streams] is empty while [loading]. */
data class StreamPickerState(
    val title: String,
    val subtitle: String?,
    val loading: Boolean,
    val streams: List<StreamSource> = emptyList(),
    val failedAddons: List<String> = emptyList(),
)

data class PlaybackUiState(
    val picker: StreamPickerState? = null,
    /** Shown while auto-play looks up streams, e.g. `Finding streams…`. */
    val busyLabel: String? = null,
)

sealed interface PlaybackEvent {
    data class Launch(val spec: ExternalLaunchSpec) : PlaybackEvent

    data class Message(val text: String) : PlaybackEvent
}

/**
 * Starts playback for Details and Episodes (PRD §9): looks up streams from every enabled addon, then
 * auto-plays the top one (preferred addon first) or opens the stream list; a long-press on `Play`
 * always opens the list. Streams go to the configured external player; when it returns, its result
 * is saved as watch progress and, for a finished episode, Auto-Play Next Episode starts the next one.
 */
@HiltViewModel
class PlaybackViewModel @Inject constructor(
    private val settings: SettingsDataStore,
    private val resolveStreams: ResolveStreamsUseCase,
    private val recorder: PlaybackProgressRecorder,
    private val titles: TitleDetailsRepository,
    private val clock: Clock,
) : ViewModel() {

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    private val _events = Channel<PlaybackEvent>(Channel.BUFFERED)
    val events: Flow<PlaybackEvent> = _events.receiveAsFlow()

    private var lookup: Job? = null
    private var pickerContext: PickerContext? = null

    /** The playback handed to an external player and not returned yet. */
    private var active: ActivePlayback? = null

    /**
     * Plays [target]; [forcePicker] (long-press, `View streams`) always shows the stream list, and
     * [forceAutoPlay] (the episode menu's `Auto-play`) always plays the top stream.
     */
    fun play(target: PlaybackTarget, forcePicker: Boolean, forceAutoPlay: Boolean = false) {
        lookup?.cancel()
        lookup = viewModelScope.launch {
            val current = settings.settings.first()
            val target = withEpisodeInfo(target)
            val autoPlay = !forcePicker && (forceAutoPlay || current.player.autoPlayTopStream)
            if (autoPlay) {
                _state.update { it.copy(busyLabel = "Finding streams…") }
            } else {
                _state.update { it.copy(picker = StreamPickerState(target.title(), target.subtitle(), loading = true)) }
            }
            val resolution = resolveStreams(StreamQuery(target.details.ref, target.imdbId, target.episode))
            if (autoPlay) {
                _state.update { it.copy(busyLabel = null) }
                val top = topStream(resolution.streams, current.player.preferredAutoPlayAddonUrl)
                if (top == null) message(noStreamsText(resolution)) else start(target, top, resolution.subtitles, current)
            } else {
                pickerContext = PickerContext(target, resolution.subtitles)
                _state.update {
                    it.copy(
                        picker = it.picker?.copy(
                            loading = false,
                            streams = resolution.streams,
                            failedAddons = resolution.failedAddons,
                        ),
                    )
                }
            }
        }
    }

    fun choose(stream: StreamSource) {
        val context = pickerContext ?: return
        _state.update { it.copy(picker = null) }
        pickerContext = null
        viewModelScope.launch { start(context.target, stream, context.subtitles, settings.settings.first()) }
    }

    fun dismissPicker() {
        lookup?.cancel()
        pickerContext = null
        _state.update { it.copy(picker = null) }
    }

    /** The external player returned; [extras] are its result extras (empty when it sent none). */
    fun onExternalResult(extras: Map<String, Any?>) {
        val playback = active ?: return
        active = null
        viewModelScope.launch {
            val outcome = ExternalPlaybackProgress.outcome(ExternalPlayerExtras.from(extras), playback.launch, clock.millis())
            recorder.record(playback.target, outcome)
            val current = settings.settings.first()
            if (outcome.completed && playback.target.episode != null && current.player.autoPlayNextEpisode) {
                playNextEpisode(playback.target, current)
            }
        }
    }

    /** The chosen player couldn't be started (e.g. it was uninstalled). */
    fun onLaunchFailed() {
        active = null
        viewModelScope.launch { message("That video player isn't installed. Pick another in Settings.") }
    }

    private suspend fun start(target: PlaybackTarget, stream: StreamSource, subtitles: List<SubtitleTrack>, current: AppSettings) {
        val choice = current.player.playerChoice
        if (choice == PlayerChoice.BuiltIn) {
            message("The built-in player isn't available yet. Choose an external player in Settings.")
            return
        }
        val preferred = current.subtitles.preferredLanguage
        val ordered = subtitles.sortedByDescending { subtitleLanguageMatches(it.language, preferred) }
            .filter { !current.subtitles.hideNonPreferredLanguages || subtitleLanguageMatches(it.language, preferred) }
            .take(MAX_SUBTITLES)
        val enabled = if (current.subtitles.mode == SubtitleMode.ALWAYS_ON) {
            ordered.firstOrNull { subtitleLanguageMatches(it.language, preferred) }
        } else {
            null
        }
        val startPosition = recorder.resumePositionMs(target)
        active = ActivePlayback(target, ExternalPlaybackLaunch(startPosition, target.knownDurationMs, clock.millis()))
        _events.send(PlaybackEvent.Launch(ExternalLaunchSpec(choice, stream, target.title(), startPosition, ordered, enabled)))
    }

    private suspend fun playNextEpisode(finished: PlaybackTarget, current: AppSettings) {
        val episode = finished.episode ?: return
        val details = finished.details
        val next = EpisodeNavigator.next(episode, details.seasons, details.lastAired)
        if (next == null) {
            message("You're all caught up on ${details.media.title}")
            return
        }
        val target = withEpisodeInfo(PlaybackTarget(finished.profileId, details, finished.imdbId, next))
        message("Up next: ${target.subtitle()}")
        _state.update { it.copy(busyLabel = "Finding streams…") }
        val resolution = resolveStreams(StreamQuery(details.ref, target.imdbId, next))
        _state.update { it.copy(busyLabel = null) }
        val top = topStream(resolution.streams, current.player.preferredAutoPlayAddonUrl)
        if (top == null) message(noStreamsText(resolution)) else start(target, top, resolution.subtitles, current)
    }

    /** Fills in the episode's title and runtime (for watch progress and the resume estimate). */
    private suspend fun withEpisodeInfo(target: PlaybackTarget): PlaybackTarget {
        val ref = target.episode ?: return target
        if (target.episodeTitle != null) return target
        val episode = orNullUnlessCancelled { titles.episodes(target.details.media.tmdbId, ref.season) }
            ?.firstOrNull { it.ref == ref }
            ?: return target
        return target.copy(episodeTitle = episode.title, episodeRuntimeMinutes = episode.runtimeMinutes)
    }

    private suspend fun message(text: String) {
        _events.send(PlaybackEvent.Message(text))
    }

    private class PickerContext(val target: PlaybackTarget, val subtitles: List<SubtitleTrack>)

    private class ActivePlayback(val target: PlaybackTarget, val launch: ExternalPlaybackLaunch)

    private companion object {
        const val MAX_SUBTITLES = 12
    }
}

private fun PlaybackTarget.title(): String = details.media.title

/** `S1:E2 · Pilot` for episodes, `null` for movies. */
private fun PlaybackTarget.subtitle(): String? {
    val ref: EpisodeRef = episode ?: return null
    val badge = MetadataFormat.episodeBadge(ref.season, ref.episode) ?: return null
    return listOfNotNull(badge, episodeTitle).joinToString(" · ")
}

private fun noStreamsText(resolution: StreamResolution): String =
    if (resolution.failedAddons.isEmpty()) "No streams found" else "No streams found (${resolution.failedAddons.joinToString()} didn't respond)"
