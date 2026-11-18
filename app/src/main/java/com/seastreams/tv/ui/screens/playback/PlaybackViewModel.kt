package com.seastreams.tv.ui.screens.playback

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.seastreams.tv.core.datastore.AppSettings
import com.seastreams.tv.core.datastore.PlayerChoice
import com.seastreams.tv.core.datastore.SettingsDataStore
import com.seastreams.tv.core.util.orNullUnlessCancelled
import com.seastreams.tv.domain.model.EpisodeRef
import com.seastreams.tv.domain.model.PlaybackRequest
import com.seastreams.tv.domain.model.StreamSource
import com.seastreams.tv.domain.model.SubtitleTrack
import com.seastreams.tv.domain.playback.AutoNextPolicy
import com.seastreams.tv.domain.playback.ExternalPlaybackLaunch
import com.seastreams.tv.domain.playback.ExternalPlaybackProgress
import com.seastreams.tv.domain.playback.ExternalPlayerExtras
import com.seastreams.tv.domain.playback.NextEpisode
import com.seastreams.tv.domain.playback.PlaybackProgressRecorder
import com.seastreams.tv.domain.playback.PlaybackStreamResolver
import com.seastreams.tv.domain.playback.PlaybackTarget
import com.seastreams.tv.domain.playback.ResolvedPlayback
import com.seastreams.tv.domain.playback.SubtitleOption
import com.seastreams.tv.domain.playback.SubtitleSource
import com.seastreams.tv.domain.playback.TrackOrdering
import com.seastreams.tv.domain.usecase.ResolveTrailerStreamUseCase
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
    /** The built-in player's current request; `null` while it is closed. */
    val player: PlaybackRequest? = null,
)

sealed interface PlaybackEvent {
    data class Launch(val spec: ExternalLaunchSpec) : PlaybackEvent

    data class Message(val text: String) : PlaybackEvent

    /** Trailer extraction failed: open the trailer in the YouTube app instead (Details only). */
    data class OpenYouTube(val youtubeKey: String) : PlaybackEvent
}

/**
 * Starts playback for Details and Episodes (PRD §9): looks up streams from every enabled addon, then
 * auto-plays the top one (preferred addon first) or opens the stream list; a long-press on `Play`
 * always opens the list. Streams open in the built-in player ([PlaybackUiState.player]) or go to the
 * configured external player; when that returns, its result is saved as watch progress and, for a
 * finished episode, Auto-Play Next Episode starts the next one. Details trailers always use the
 * built-in player, falling back to YouTube only when extraction fails.
 */
@HiltViewModel
class PlaybackViewModel @Inject constructor(
    private val settings: SettingsDataStore,
    private val streams: PlaybackStreamResolver,
    private val recorder: PlaybackProgressRecorder,
    private val resolveTrailer: ResolveTrailerStreamUseCase,
    private val clock: Clock,
) : ViewModel() {

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    private val _events = Channel<PlaybackEvent>(Channel.BUFFERED)
    val events: Flow<PlaybackEvent> = _events.receiveAsFlow()

    private var lookup: Job? = null
    private var pickerContext: ResolvedPlayback? = null
    private var nextSessionId = 1L

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
            val autoPlay = !forcePicker && (forceAutoPlay || current.player.autoPlayTopStream)
            if (autoPlay) {
                _state.update { it.copy(busyLabel = "Finding streams…") }
            } else {
                _state.update { it.copy(picker = StreamPickerState(target.title(), target.subtitle(), loading = true)) }
            }
            val resolved = streams.resolve(target, current.player.preferredAutoPlayAddonUrl)
            if (autoPlay) {
                _state.update { it.copy(busyLabel = null) }
                if (resolved.top == null) message(noStreamsMessage(resolved)) else start(resolved, resolved.topIndex, current)
            } else {
                pickerContext = resolved
                val resolution = resolved.resolution
                _state.update {
                    it.copy(
                        picker = it.picker?.copy(
                            title = resolved.target.title(),
                            subtitle = resolved.target.subtitle(),
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
        pickerContext = null
        val index = context.resolution.streams.indexOf(stream).coerceAtLeast(0)
        viewModelScope.launch { start(context, index, settings.settings.first()) }
    }

    fun dismissPicker() {
        lookup?.cancel()
        pickerContext = null
        _state.update { it.copy(picker = null) }
    }

    /** Details `Trailer`: plays full-screen in the built-in player, or opens YouTube if extraction fails. */
    fun playTrailer(title: String, youtubeKey: String) {
        lookup?.cancel()
        lookup = viewModelScope.launch {
            _state.update { it.copy(busyLabel = "Loading trailer…") }
            val stream = orNullUnlessCancelled { resolveTrailer(youtubeKey) }
            _state.update { it.copy(busyLabel = null) }
            if (stream == null) {
                _events.send(PlaybackEvent.OpenYouTube(youtubeKey))
            } else {
                _state.update { it.copy(player = PlaybackRequest.Trailer(nextSessionId++, title, stream)) }
            }
        }
    }

    /** The built-in player exited (Back, trailer end, or nothing left to play). */
    fun closePlayer() = _state.update { it.copy(player = null) }

    /** The external player returned; [extras] are its result extras (empty when it sent none). */
    fun onExternalResult(extras: Map<String, Any?>) {
        val playback = active ?: return
        active = null
        viewModelScope.launch {
            val outcome = ExternalPlaybackProgress.outcome(ExternalPlayerExtras.from(extras), playback.launch, clock.millis())
            recorder.record(playback.target, outcome)
            val current = settings.settings.first()
            if (AutoNextPolicy.afterExternalReturn(playback.target, outcome, current.player.autoPlayNextEpisode)) {
                playNextEpisode(playback.target, current)
            }
        }
    }

    /** The chosen player couldn't be started (e.g. it was uninstalled). */
    fun onLaunchFailed() {
        active = null
        viewModelScope.launch { message("That video player isn't installed. Pick another in Settings.") }
    }

    private suspend fun start(resolved: ResolvedPlayback, index: Int, current: AppSettings) {
        val target = resolved.target
        val resolution = resolved.resolution
        val stream = resolution.streams.getOrNull(index) ?: return
        val startPosition = recorder.resumePositionMs(target)
        val choice = current.player.playerChoice
        if (choice == PlayerChoice.BuiltIn) {
            val request = PlaybackRequest.Media(nextSessionId++, target, resolution.streams, index, resolution.subtitles, startPosition)
            _state.update { it.copy(picker = null, player = request) }
            return
        }
        _state.update { it.copy(picker = null) }
        val prefs = current.subtitles
        val options = resolution.subtitles.map { SubtitleOption(it.url, it.addonName, it.language, SubtitleSource.EXTERNAL) }
        val ordered = TrackOrdering.subtitles(options, prefs.preferredLanguage, prefs.hideNonPreferredLanguages).take(MAX_SUBTITLES)
        val byUrl = resolution.subtitles.associateBy(SubtitleTrack::url)
        val enabled = TrackOrdering.initialSubtitle(ordered, prefs.mode, prefs.preferredLanguage)?.let { byUrl[it.id] }
        active = ActivePlayback(target, ExternalPlaybackLaunch(startPosition, target.knownDurationMs, clock.millis()))
        _events.send(
            PlaybackEvent.Launch(ExternalLaunchSpec(choice, stream, target.title(), startPosition, ordered.mapNotNull { byUrl[it.id] }, enabled)),
        )
    }

    private suspend fun playNextEpisode(finished: PlaybackTarget, current: AppSettings) {
        _state.update { it.copy(busyLabel = "Finding streams…") }
        val next = streams.next(finished, current.player.preferredAutoPlayAddonUrl)
        _state.update { it.copy(busyLabel = null) }
        when (next) {
            NextEpisode.CaughtUp -> message("You're all caught up on ${finished.details.media.title}")
            is NextEpisode.Found -> {
                val resolved = next.playback
                message("Up next: ${resolved.target.subtitle()}")
                if (resolved.top == null) message(noStreamsMessage(resolved)) else start(resolved, resolved.topIndex, current)
            }
        }
    }

    private suspend fun message(text: String) {
        _events.send(PlaybackEvent.Message(text))
    }

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
