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

    data class OpenTrailer(val youtubeKey: String) : DetailsEvent
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
        loadJob?.cancel()
        _state.value = DetailsUiState(profileId = profileId, media = item)
        loadJob = viewModelScope.launch { load(profileId, item) }
    }

    fun retry() {
        val current = _state.value
        val profileId = current.profileId ?: return
        val item = current.media ?: return
        loadJob?.cancel()
        _state.value = DetailsUiState(profileId = profileId, media = item)
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

    fun requestTrailer() {
        val ref = _state.value.media?.ref ?: return
        viewModelScope.launch {
            val key = orNullUnlessCancelled { titles.trailerKey(ref) }
            _events.send(if (key == null) DetailsEvent.Message("No trailer available") else DetailsEvent.OpenTrailer(key))
        }
    }

    /**
     * What `Play` starts ([episode] overrides the next-up episode, e.g. for Shuffle), once the title
     * and its IMDb id are loaded. `null` when the title couldn't be loaded.
     */
    suspend fun awaitPlayTarget(episode: EpisodeRef? = null): PlaybackTarget? {
        val ready = _state.first { it.content !is DetailsContent.Loading && it.imdbResolved || it.content.isTerminalFailure() }
        val details = ready.details ?: return null
        val profileId = ready.profileId ?: return null
        val chosen = if (ready.isShow) episode ?: ready.nextUpEpisode ?: return null else null
        return PlaybackTarget(profileId, details, ready.imdbId, chosen)
    }

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
            progress.observe(profileId, item.ref).collect { points -> _state.update { it.copy(progress = points) } }
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
