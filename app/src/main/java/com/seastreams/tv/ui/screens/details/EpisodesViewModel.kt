package com.seastreams.tv.ui.screens.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.seastreams.tv.core.datastore.SettingsDataStore
import com.seastreams.tv.core.util.orNullUnlessCancelled
import com.seastreams.tv.data.details.TitleDetailsRepository
import com.seastreams.tv.domain.model.Episode
import com.seastreams.tv.domain.model.EpisodeRef
import com.seastreams.tv.domain.model.TitleDetails
import com.seastreams.tv.domain.playback.EpisodeNavigator
import com.seastreams.tv.domain.playback.EpisodeWatchMarker
import com.seastreams.tv.domain.playback.PlaybackProgressRecorder
import com.seastreams.tv.domain.playback.PlaybackTarget
import com.seastreams.tv.domain.playback.ProgressPoint
import com.seastreams.tv.domain.playback.SeasonEpisodeSelection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface SeasonEpisodes {
    data object Loading : SeasonEpisodes

    data class Loaded(val episodes: List<Episode>) : SeasonEpisodes

    data object Failed : SeasonEpisodes
}

data class EpisodesUiState(
    val profileId: Long? = null,
    val details: TitleDetails? = null,
    val imdbId: String? = null,
    val selectedSeason: Int = 0,
    val seasons: Map<Int, SeasonEpisodes> = emptyMap(),
    val progress: List<ProgressPoint> = emptyList(),
    val blurUnwatched: Boolean = false,
    /** Episode the list first focuses: the show's next-up episode. */
    val nextUp: EpisodeRef? = null,
) {
    val selected: SeasonEpisodes get() = seasons[selectedSeason] ?: SeasonEpisodes.Loading

    fun progressOf(ref: EpisodeRef): ProgressPoint? = progress.firstOrNull { it.episode == ref }

    /** Never started: its still is blurred when *Blur unwatched episode thumbnails* is on. */
    fun isUnwatched(ref: EpisodeRef): Boolean = progressOf(ref)?.let { it.positionMs <= 0 && !it.completed } ?: true

    /** Finished (`>= 90%` or marked watched): the episode menu offers `Mark unwatched`. */
    fun isWatched(ref: EpisodeRef): Boolean = progressOf(ref)?.completed == true
}

/** The Episodes screen (`episodes-page.jpg`) for the show open on Details. */
@HiltViewModel
class EpisodesViewModel @Inject constructor(
    private val titles: TitleDetailsRepository,
    private val progress: PlaybackProgressRecorder,
    private val settings: SettingsDataStore,
    private val marker: EpisodeWatchMarker,
) : ViewModel() {

    private val _state = MutableStateFlow(EpisodesUiState())
    val state: StateFlow<EpisodesUiState> = _state.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)

    /** Short notices for the screen's toast, e.g. when a season's episodes can't be loaded. */
    val messages: Flow<String> = _messages.receiveAsFlow()

    private var job: Job? = null
    private val seasonJobs = mutableMapOf<Int, Job>()

    fun open(profileId: Long, details: TitleDetails, imdbId: String?) {
        val current = _state.value
        if (current.profileId == profileId && current.details?.ref == details.ref) return
        job?.cancel()
        seasonJobs.values.forEach { it.cancel() }
        seasonJobs.clear()
        _state.value = EpisodesUiState(profileId = profileId, details = details, imdbId = imdbId)
        job = viewModelScope.launch {
            coroutineScope {
                launch {
                    settings.settings.map { it.player.blurUnwatchedThumbnails }.distinctUntilChanged()
                        .collect { blur -> _state.update { it.copy(blurUnwatched = blur) } }
                }
                val points = progress.observe(profileId, details.ref)
                // Open on the season of the next-up episode.
                val nextUp = EpisodeNavigator.nextUp(points.first(), details.seasons, details.lastAired)
                val season = nextUp?.season ?: details.seasons.firstOrNull()?.number ?: 0
                _state.update { it.copy(nextUp = nextUp, selectedSeason = season) }
                selectSeason(season)
                points.collect { list -> _state.update { it.copy(progress = list) } }
            }
        }
    }

    /** Shows [season] and loads its episodes once (focus on a season pill selects it). */
    fun selectSeason(season: Int) {
        _state.update { it.copy(selectedSeason = season) }
        val details = _state.value.details ?: return
        if (_state.value.seasons[season] is SeasonEpisodes.Loaded || seasonJobs[season]?.isActive == true) return
        _state.update { it.copy(seasons = it.seasons + (season to SeasonEpisodes.Loading)) }
        seasonJobs[season] = viewModelScope.launch {
            val result = orNullUnlessCancelled { titles.episodes(details.media.tmdbId, season) }
                ?.let { SeasonEpisodes.Loaded(it) }
                ?: SeasonEpisodes.Failed
            _state.update { it.copy(seasons = it.seasons + (season to result)) }
        }
    }

    fun target(episode: Episode): PlaybackTarget? {
        val current = _state.value
        val details = current.details ?: return null
        val profileId = current.profileId ?: return null
        return PlaybackTarget(profileId, details, current.imdbId, episode.ref, episode.title, episode.runtimeMinutes)
    }

    /** `Mark Season Watched` / `Mark Season Unwatched`: every episode of [season], whatever its state. */
    fun markSeason(season: Int, watched: Boolean) = mark(season, watched) { SeasonEpisodeSelection.season(it, season) }

    /** `Mark previous in season watched` / `unwatched`: episode 1 of its season through [episode]. */
    fun markThrough(episode: Episode, watched: Boolean) =
        mark(episode.ref.season, watched) { SeasonEpisodeSelection.throughEpisode(it, episode.ref) }

    /** `Mark watched` / `Mark unwatched` on one episode, flipping its current state. */
    fun toggleWatched(episode: Episode) {
        val current = _state.value
        val details = current.details ?: return
        val profileId = current.profileId ?: return
        val watched = !current.isWatched(episode.ref)
        viewModelScope.launch { apply(profileId, details, listOf(episode), watched) }
    }

    /** `Shuffle Season`: a random aired episode of [season] only, ready to play; `null` if it has none. */
    suspend fun shuffleTarget(season: Int): PlaybackTarget? {
        val details = _state.value.details ?: return null
        val episodes = episodesOf(details, season) ?: return null
        return SeasonEpisodeSelection.shuffle(episodes, season, details.lastAired)?.let(::target)
    }

    private fun mark(season: Int, watched: Boolean, pick: (List<Episode>) -> List<Episode>) {
        val current = _state.value
        val details = current.details ?: return
        val profileId = current.profileId ?: return
        viewModelScope.launch {
            val episodes = episodesOf(details, season)
            if (episodes == null) _messages.send(SEASON_FAILED) else apply(profileId, details, pick(episodes), watched)
        }
    }

    private suspend fun apply(profileId: Long, details: TitleDetails, episodes: List<Episode>, watched: Boolean) {
        if (watched) {
            marker.markWatched(profileId, details, episodes)
        } else {
            marker.markUnwatched(profileId, details, episodes.map { it.ref })
        }
    }

    /** The season's episodes: already on screen, else fetched (cached by the repository). */
    private suspend fun episodesOf(details: TitleDetails, season: Int): List<Episode>? =
        (_state.value.seasons[season] as? SeasonEpisodes.Loaded)?.episodes
            ?: orNullUnlessCancelled { titles.episodes(details.media.tmdbId, season) }

    private companion object {
        const val SEASON_FAILED = "Couldn't load this season"
    }
}
