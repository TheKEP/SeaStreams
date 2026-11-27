package com.seastreams.tv.ui.screens.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.seastreams.tv.core.util.orNullUnlessCancelled
import com.seastreams.tv.data.details.RestrictedTitleException
import com.seastreams.tv.data.details.TitleDetailsRepository
import com.seastreams.tv.data.library.LibraryAddResult
import com.seastreams.tv.data.library.LibraryRepository
import com.seastreams.tv.data.profile.ProfileRepository
import com.seastreams.tv.domain.model.EpisodeRef
import com.seastreams.tv.domain.model.MediaItem
import com.seastreams.tv.domain.playback.EpisodeNavigator
import com.seastreams.tv.domain.playback.PlaybackProgressRecorder
import com.seastreams.tv.domain.playback.PlaybackTarget
import com.seastreams.tv.domain.playback.ShuffleMode
import com.seastreams.tv.domain.playback.ShuffleSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One-off effects for the Details screen. */
sealed interface DetailsEvent {
    data class Message(val text: String) : DetailsEvent

    /** [youtubeKeys]: ranked TMDB trailer candidates, never empty. */
    data class OpenTrailer(val title: String, val youtubeKeys: List<String>) : DetailsEvent
}

/**
 * Details for one title at a time (Details is a single full-screen layer). Opening it loads the TMDB
 * record (age-checked in [TitleDetailsRepository.load]) and then — only here — the IMDb id that
 * Stremio addons need.
 */
@HiltViewModel
class DetailsViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val titles: TitleDetailsRepository,
    private val library: LibraryRepository,
    private val progress: PlaybackProgressRecorder,
) : ViewModel() {

    private val _state = MutableStateFlow(DetailsUiState())
    val state: StateFlow<DetailsUiState> = _state.asStateFlow()

    private val _events = Channel<DetailsEvent>(Channel.BUFFERED)
    val events: Flow<DetailsEvent> = _events.receiveAsFlow()

    private var loadJob: Job? = null

    fun open(profileId: Long, item: MediaItem) {
        val current = _state.value
        if (current.profileId == profileId && current.media?.ref == item.ref && current.content != DetailsContent.Failed) return
        restart(profileId, item)
    }

    fun retry() {
        val current = _state.value
        restart(current.profileId ?: return, current.media ?: return)
    }

    private fun restart(profileId: Long, item: MediaItem) {
        loadJob?.cancel()
        // The remembered shuffle is session-wide; shuffleContinuation applies it only to its own title.
        _state.value = DetailsUiState(profileId = profileId, media = item, shuffleResume = _state.value.shuffleResume)
        loadJob = viewModelScope.launch { load(profileId, item) }
    }

    /** `Add to library` / `Remove from library`; a repeated add reports "Already in library". */
    fun toggleLibrary() {
        val current = _state.value
        val profileId = current.profileId ?: return
        val details = current.details ?: return
        viewModelScope.launch {
            if (current.inLibrary) {
                library.remove(profileId, details.ref)
            } else if (library.add(profileId, details) == LibraryAddResult.ALREADY_IN_LIBRARY) {
                _events.send(DetailsEvent.Message(ALREADY_IN_LIBRARY))
            }
        }
    }

    /**
     * Movie `Clear progress`: deletes the movie's progress record for the active profile. The state
     * drops the movie's point synchronously, so the frame that moves focus to `Play` also flips its
     * label and removes the button; Room's later re-emission is an equal state and recomposes nothing.
     */
    fun clearProgress() {
        val current = _state.value
        if (!current.canClearProgress) return
        val profileId = current.profileId ?: return
        val media = current.media ?: return
        _state.update { s -> s.copy(progress = s.progress.filterNot { it.episode == null }) }
        viewModelScope.launch { progress.clearMovieProgress(profileId, media.ref) }
    }

    fun requestTrailer() {
        val media = _state.value.media ?: return
        viewModelScope.launch {
            val keys = orNullUnlessCancelled { titles.trailerKeys(media.ref) }.orEmpty()
            _events.send(if (keys.isEmpty()) DetailsEvent.Message("No trailer available") else DetailsEvent.OpenTrailer(media.title, keys))
        }
    }

    /**
     * What `Play` starts ([episode] overrides the next-up episode, e.g. for Shuffle), once the title
     * and its IMDb id are loaded. [shuffle] starts a shuffle session (`Shuffle` passes [ShuffleMode.Show])
     * that Next Episode / Auto-Play Next Episode follow. `Play`/`Resume` are sequential, except that while a
     * shuffle of this show is active they continue it ([DetailsUiState.shuffleContinuation]). `null` when
     * the title couldn't be loaded. Waits for the title, its IMDb id and its saved progress.
     */
    suspend fun awaitPlayTarget(episode: EpisodeRef? = null, shuffle: ShuffleMode = ShuffleMode.None): PlaybackTarget? {
        // Saved progress too (actionsReady): without it a show's next-up episode reads as S1:E1, so
        // `Resume S2:E4` would play — and look up the LAST USED stream of — the wrong episode.
        val ready = _state.first {
            it.content !is DetailsContent.Loading && it.imdbResolved && it.actionsReady || it.content.isTerminalFailure()
        }
        val details = ready.details ?: return null
        val profileId = ready.profileId ?: return null
        // Play / Resume during an active shuffle of this show continues it, session and all (PRD §6).
        if (episode == null && shuffle == ShuffleMode.None) ready.shuffleContinuation?.let { return it.copy(imdbId = ready.imdbId ?: it.imdbId) }
        val chosen = if (ready.isShow) episode ?: ready.nextUpEpisode ?: return null else null
        val session = chosen?.let { ShuffleSession.start(shuffle, it) } ?: ShuffleSession.Sequential
        return PlaybackTarget(profileId, details, ready.imdbId, chosen, shuffle = session)
    }

    /** The last shuffled episode played (`PlaybackViewModel.shuffleResume`); `null` once that shuffle ended. */
    fun setShuffleResume(target: PlaybackTarget?) = _state.update { it.copy(shuffleResume = target) }

    /** A random aired episode for `Shuffle`; `null` for movies or before seasons are known. */
    fun shuffleEpisode(): EpisodeRef? {
        val details = _state.value.details ?: return null
        return EpisodeNavigator.shuffle(details.seasons, details.lastAired)
    }

    /** Runs until the next [open]/[retry] cancels it: the library and progress collectors live as long. */
    private suspend fun load(profileId: Long, item: MediaItem) = coroutineScope {
        val profile = profiles.getProfile(profileId)
        if (profile == null) {
            _state.update { it.copy(content = DetailsContent.Failed) }
            return@coroutineScope
        }
        launch {
            library.observeContains(profileId, item.ref).collect { saved -> _state.update { it.copy(inLibrary = saved) } }
        }
        launch {
            progress.observe(profileId, item.ref).collect { points -> _state.update { it.copy(progress = points, progressLoaded = true) } }
        }
        val details = try {
            titles.load(item.ref, profile.toAgeRestriction())
        } catch (e: CancellationException) {
            throw e
        } catch (e: RestrictedTitleException) {
            _state.update { it.copy(content = DetailsContent.Restricted) }
            return@coroutineScope
        } catch (e: Exception) {
            _state.update { it.copy(content = DetailsContent.Failed) }
            return@coroutineScope
        }
        _state.update { it.copy(media = details.media, content = DetailsContent.Loaded(details)) }
        val imdbId = orNullUnlessCancelled { titles.imdbIdForDetails(item.ref) }
        _state.update { it.copy(imdbResolved = true, imdbId = imdbId) }
    }

    companion object {
        const val ALREADY_IN_LIBRARY = "Already in library"
    }
}

private fun DetailsContent.isTerminalFailure(): Boolean = this == DetailsContent.Failed || this == DetailsContent.Restricted
