package com.seastreams.tv.ui.screens.playback

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.seastreams.tv.core.datastore.AppSettings
import com.seastreams.tv.core.datastore.PlayerChoice
import com.seastreams.tv.core.datastore.SettingsDataStore
import com.seastreams.tv.core.util.orNullUnlessCancelled
import com.seastreams.tv.domain.model.ContinueWatchingItem
import com.seastreams.tv.domain.model.EpisodeRef
import com.seastreams.tv.domain.model.PlaybackArtwork
import com.seastreams.tv.domain.model.PlaybackRequest
import com.seastreams.tv.domain.model.playbackArtwork
import com.seastreams.tv.domain.model.StreamAddon
import com.seastreams.tv.domain.model.StreamSource
import com.seastreams.tv.domain.model.SubtitleTrack
import com.seastreams.tv.domain.playback.AutoNextPolicy
import com.seastreams.tv.domain.playback.ExternalPlaybackLaunch
import com.seastreams.tv.domain.playback.ExternalPlaybackProgress
import com.seastreams.tv.domain.playback.ExternalPlayerExtras
import com.seastreams.tv.domain.playback.LastUsedStreamRecorder
import com.seastreams.tv.domain.playback.NextEpisode
import com.seastreams.tv.domain.playback.PlaybackProgressRecorder
import com.seastreams.tv.domain.playback.PlaybackStreamResolver
import com.seastreams.tv.domain.playback.PlaybackTarget
import com.seastreams.tv.domain.playback.ResolvedPlayback
import com.seastreams.tv.domain.playback.SubtitleOption
import com.seastreams.tv.domain.playback.SubtitleSource
import com.seastreams.tv.domain.playback.TrackOrdering
import com.seastreams.tv.domain.usecase.ContinueWatchingTarget
import com.seastreams.tv.domain.usecase.ResolveContinueWatchingTargetUseCase
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

/**
 * The stream list modal. [streams] is empty while [loading]; [lastUsedIndex] is the stream that last
 * started for this movie/episode on this profile (`LAST USED` badge and initial focus), if listed.
 */
data class StreamPickerState(
    val title: String,
    val subtitle: String?,
    val loading: Boolean,
    val streams: List<StreamSource> = emptyList(),
    val failedAddons: List<String> = emptyList(),
    val lastUsedIndex: Int? = null,
    /** The addon cards above the list, in addon order (arrives with [streams]). */
    val streamAddons: List<StreamAddon> = emptyList(),
)

data class PlaybackUiState(
    val picker: StreamPickerState? = null,
    /** Shown while auto-play looks up streams, e.g. `Finding streams…`. */
    val busyLabel: String? = null,
    /** The built-in player's current request; `null` while it is closed. */
    val player: PlaybackRequest? = null,
    /**
     * Full-screen loading branding while auto-play looks up streams, before the player opens (PRD §9
     * Player Loading & Buffering UI); `null` otherwise. Cleared in the same update that opens the player.
     */
    val loading: PlaybackArtwork? = null,
)

sealed interface PlaybackEvent {
    data class Launch(val spec: ExternalLaunchSpec) : PlaybackEvent

    data class Message(val text: String) : PlaybackEvent

    /** Trailer extraction failed: open the trailer in the YouTube app instead (Details only). */
    data class OpenYouTube(val youtubeKey: String) : PlaybackEvent
}

/**
 * Starts playback for Details, Episodes and Continue Watching auto-play (PRD §3, §9): looks up
 * streams from every enabled addon, then auto-plays the `LAST USED` stream when the fresh list holds
 * it, else the top one (preferred addon first) — [LastUsedStreamRecorder.autoPlayIndex] — or opens the
 * stream list; a long-press on `Play` always opens the list. Streams open in the built-in player ([PlaybackUiState.player]) or go to the
 * configured external player; when that returns, its result is saved as watch progress and, for a
 * finished episode, Auto-Play Next Episode starts the next one. Details trailers always use the
 * built-in player, falling back to YouTube only when extraction fails.
 */
@HiltViewModel
class PlaybackViewModel @Inject constructor(
    private val settings: SettingsDataStore,
    private val streams: PlaybackStreamResolver,
    private val recorder: PlaybackProgressRecorder,
    private val lastUsed: LastUsedStreamRecorder,
    private val resolveTrailer: ResolveTrailerStreamUseCase,
    private val resolveContinueWatching: ResolveContinueWatchingTargetUseCase,
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
     * [forceAutoPlay] (the episode menu's `Auto-play`) always auto-plays (LAST USED, else top stream).
     */
    fun play(target: PlaybackTarget, forcePicker: Boolean, forceAutoPlay: Boolean = false) {
        lookup?.cancel()
        lookup = viewModelScope.launch { lookUp(target, forcePicker, forceAutoPlay) }
    }

    /**
     * A Continue Watching card with *Auto-play from Continue Watching* on (PRD §3): straight from Home
     * to the card's movie or episode — its `LAST USED` stream, else the top stream (preferred addon
     * first), whatever *Auto-play top stream* says — at its saved position — without opening Details. On failure only a message shows,
     * so focus stays on the card.
     */
    fun playContinueWatching(profileId: Long, item: ContinueWatchingItem) {
        lookup?.cancel()
        lookup = viewModelScope.launch {
            _state.update { it.copy(busyLabel = FINDING_STREAMS, loading = cardArtwork(item)) }
            val resolved = try {
                resolveContinueWatching(profileId, item)
            } finally {
                _state.update { it.copy(busyLabel = null) }
            }
            // `loading` stays up into lookUp so the layer (and focus) never flickers between the steps.
            when (resolved) {
                is ContinueWatchingTarget.Ready -> lookUp(resolved.target, forcePicker = false, forceAutoPlay = true)
                ContinueWatchingTarget.Restricted -> stopLoading(RESTRICTED_TITLE)
                ContinueWatchingTarget.Unavailable -> stopLoading(UNAVAILABLE_TITLE)
            }
        }
    }

    private suspend fun lookUp(target: PlaybackTarget, forcePicker: Boolean, forceAutoPlay: Boolean) {
        val current = settings.settings.first()
        val autoPlay = !forcePicker && (forceAutoPlay || current.player.autoPlayTopStream)
        if (autoPlay) {
            _state.update { it.copy(busyLabel = FINDING_STREAMS, loading = target.details.media.playbackArtwork()) }
        } else {
            _state.update { it.copy(picker = StreamPickerState(target.title(), target.subtitle(), loading = true)) }
        }
        val resolved = streams.resolve(target, current.player.preferredAutoPlayAddonUrl)
        if (autoPlay) {
            _state.update { it.copy(busyLabel = null) }
            if (resolved.top == null) stopLoading(noStreamsMessage(resolved)) else start(resolved, lastUsed.autoPlayIndex(resolved), current)
        } else {
            pickerContext = resolved
            val resolution = resolved.resolution
            // Computed before the update so the cards and their initial focus arrive together.
            val lastUsedIndex = lastUsed.indexIn(resolved.target, resolution.streams)
            _state.update {
                it.copy(
                    picker = it.picker?.copy(
                        title = resolved.target.title(),
                        subtitle = resolved.target.subtitle(),
                        loading = false,
                        streams = resolution.streams,
                        streamAddons = resolution.streamAddons,
                        failedAddons = resolution.failedAddons,
                        lastUsedIndex = lastUsedIndex,
                    ),
                )
            }
        }
    }

    fun choose(stream: StreamSource) {
        val context = pickerContext ?: return
        pickerContext = null
        val index = context.resolution.streams.indexOf(stream).coerceAtLeast(0)
        viewModelScope.launch { start(context, index, settings.settings.first()) }
    }

    /** `Back` on the stream-lookup loading screen: stop looking and close it (focus returns to the opener). */
    fun cancelLoading() {
        lookup?.cancel()
        _state.update { it.copy(busyLabel = null, loading = null) }
    }

    fun dismissPicker() {
        lookup?.cancel()
        pickerContext = null
        _state.update { it.copy(picker = null) }
    }

    /**
     * Details `Trailer`: plays the first of the ranked [youtubeKeys] that extracts full-screen in the
     * built-in player, or opens the top-ranked one in YouTube if every candidate fails (PRD §9).
     */
    fun playTrailer(title: String, youtubeKeys: List<String>) {
        val topKey = youtubeKeys.firstOrNull() ?: return
        lookup?.cancel()
        lookup = viewModelScope.launch {
            _state.update { it.copy(busyLabel = "Loading trailer…") }
            val stream = orNullUnlessCancelled { resolveTrailer(youtubeKeys) }
            _state.update { it.copy(busyLabel = null) }
            if (stream == null) {
                _events.send(PlaybackEvent.OpenYouTube(topKey))
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
        val stream = resolution.streams.getOrNull(index) ?: return _state.update { it.copy(loading = null) }
        val startPosition = recorder.resumePositionMs(target)
        val choice = current.player.playerChoice
        if (choice == PlayerChoice.BuiltIn) {
            val request = PlaybackRequest.Media(
                nextSessionId++, target, resolution.streams, index, resolution.subtitles, startPosition, resolution.streamAddons,
            )
            _state.update { it.copy(picker = null, player = request, loading = null) }
            return
        }
        _state.update { it.copy(picker = null, loading = null) }
        val prefs = current.subtitles
        val options = resolution.subtitles.map { SubtitleOption(it.url, it.addonName, it.language, SubtitleSource.EXTERNAL) }
        val ordered = TrackOrdering.subtitles(options, prefs.preferredLanguage, prefs.hideNonPreferredLanguages).take(MAX_SUBTITLES)
        val byUrl = resolution.subtitles.associateBy(SubtitleTrack::url)
        val enabled = TrackOrdering.initialSubtitle(ordered, prefs.mode, prefs.preferredLanguage)?.let { byUrl[it.id] }
        // The external player gives no start signal: the hand-off is when this stream becomes LAST USED.
        lastUsed.record(target, stream)
        active = ActivePlayback(target, ExternalPlaybackLaunch(startPosition, target.knownDurationMs, clock.millis()))
        _events.send(
            PlaybackEvent.Launch(ExternalLaunchSpec(choice, stream, target.title(), startPosition, ordered.mapNotNull { byUrl[it.id] }, enabled)),
        )
    }

    private suspend fun playNextEpisode(finished: PlaybackTarget, current: AppSettings) {
        _state.update { it.copy(busyLabel = FINDING_STREAMS, loading = finished.details.media.playbackArtwork()) }
        val next = streams.next(finished, current.player.preferredAutoPlayAddonUrl)
        _state.update { it.copy(busyLabel = null) }
        when (next) {
            NextEpisode.CaughtUp -> stopLoading("You're all caught up on ${finished.details.media.title}")
            is NextEpisode.Found -> {
                val resolved = next.playback
                message("Up next: ${resolved.target.subtitle()}")
                if (resolved.top == null) stopLoading(noStreamsMessage(resolved)) else start(resolved, lastUsed.autoPlayIndex(resolved), current)
            }
        }
    }

    private suspend fun message(text: String) {
        _events.send(PlaybackEvent.Message(text))
    }

    /** Auto-play found nothing to play: close the loading screen and say why. */
    private suspend fun stopLoading(text: String) {
        _state.update { it.copy(loading = null) }
        message(text)
    }

    private class ActivePlayback(val target: PlaybackTarget, val launch: ExternalPlaybackLaunch)

    private companion object {
        const val MAX_SUBTITLES = 12
        const val FINDING_STREAMS = "Finding streams…"
        const val RESTRICTED_TITLE = "This title isn't available on this profile."
        const val UNAVAILABLE_TITLE = "Couldn't load this title. Check the connection and try again."
    }
}

private fun PlaybackTarget.title(): String = details.media.title

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
private fun PlaybackTarget.subtitle(): String? {
    val ref: EpisodeRef = episode ?: return null
    val badge = MetadataFormat.episodeBadge(ref.season, ref.episode) ?: return null
    return listOfNotNull(badge, episodeTitle).joinToString(" · ")
}
