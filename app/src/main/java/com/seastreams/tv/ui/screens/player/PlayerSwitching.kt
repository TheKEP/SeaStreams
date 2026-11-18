package com.seastreams.tv.ui.screens.player

import androidx.lifecycle.viewModelScope
import com.seastreams.tv.domain.model.EpisodeRef
import com.seastreams.tv.domain.model.PlaybackRequest
import com.seastreams.tv.domain.playback.NextEpisode
import com.seastreams.tv.domain.playback.PlaybackProgressRecorder
import com.seastreams.tv.domain.playback.PlaybackStreamResolver
import com.seastreams.tv.domain.playback.ResolvedPlayback
import com.seastreams.tv.domain.playback.forEpisode
import com.seastreams.tv.ui.components.MetadataFormat
import com.seastreams.tv.ui.screens.playback.noStreamsMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Everything that changes what the open player plays without leaving it: the Streams overlay's
 * hot-swap, `Next Episode`, the Episodes browser and Auto-Play Next Episode (top stream of the next
 * episode, preferred auto-play addon first).
 */
class PlayerSwitching @Inject constructor(
    private val resolver: PlaybackStreamResolver,
    private val recorder: PlaybackProgressRecorder,
) {
    private var switchJob: Job? = null
    private var episodesJob: Job? = null

    /** Streams overlay: same title, another stream, same position. */
    fun selectStream(vm: PlayerViewModel, index: Int) {
        val manager = vm.currentManager() ?: return
        if (index == vm._state.value.streamIndex) {
            vm.closePanel()
            return
        }
        vm.saveNow()
        val position = manager.player.currentPosition
        vm._state.update { it.copy(panel = null, hudVisible = false, status = "Switching to stream ${index + 1}…") }
        vm.playIndex(manager, index, position)
    }

    /**
     * `Next Episode` ([auto] = false) or Auto-Play Next Episode ([auto] = true). Auto-next leaves the
     * player when the show is finished or no stream is found; a manual press keeps the current episode.
     */
    fun playNextEpisode(vm: PlayerViewModel, auto: Boolean) {
        val media = vm._state.value.request as? PlaybackRequest.Media ?: return
        if (!auto) vm.saveNow()
        switchJob?.cancel()
        switchJob = vm.viewModelScope.launch {
            vm._state.update { it.copy(status = "Finding the next episode…", panel = null, hudVisible = false) }
            when (val next = resolver.next(media.target, vm.settings.player.preferredAutoPlayAddonUrl)) {
                NextEpisode.CaughtUp -> {
                    vm._state.update { it.copy(status = null) }
                    vm._messages.send("You're all caught up on ${media.title}")
                    if (auto) vm.exit()
                }
                is NextEpisode.Found -> start(vm, next.playback, leaveWhenEmpty = auto)
            }
        }
    }

    /** Episodes overlay: play [ref] of the current show. A manual pick ends any shuffle (sequential from here). */
    fun playEpisode(vm: PlayerViewModel, ref: EpisodeRef) {
        val media = vm._state.value.request as? PlaybackRequest.Media ?: return
        vm.saveNow()
        switchJob?.cancel()
        switchJob = vm.viewModelScope.launch {
            val badge = MetadataFormat.episodeBadge(ref.season, ref.episode)
            vm._state.update { it.copy(status = "Finding streams for $badge…", panel = null, hudVisible = false) }
            val target = media.target.forEpisode(ref)
            start(vm, resolver.resolve(target, vm.settings.player.preferredAutoPlayAddonUrl), leaveWhenEmpty = false)
        }
    }

    fun loadEpisodes(vm: PlayerViewModel) {
        val media = vm._state.value.request as? PlaybackRequest.Media ?: return
        val season = vm._state.value.episodes.season.takeIf { vm._state.value.episodes.episodes.isNotEmpty() }
            ?: media.target.episode?.season ?: return
        selectSeason(vm, season)
    }

    fun selectSeason(vm: PlayerViewModel, season: Int) {
        val media = vm._state.value.request as? PlaybackRequest.Media ?: return
        val details = media.target.details
        vm._state.update { it.copy(episodes = it.episodes.copy(seasons = details.seasons, season = season, loading = true)) }
        episodesJob?.cancel()
        episodesJob = vm.viewModelScope.launch {
            val list = resolver.episodes(details.media.tmdbId, season).filter { episode ->
                details.lastAired == null || episode.ref <= details.lastAired
            }
            vm._state.update {
                if (it.episodes.season != season) it else it.copy(episodes = it.episodes.copy(episodes = list, loading = false))
            }
        }
    }

    private suspend fun start(vm: PlayerViewModel, playback: ResolvedPlayback, leaveWhenEmpty: Boolean) {
        val current = vm._state.value.request as? PlaybackRequest.Media ?: return
        if (playback.top == null) {
            vm._state.update { it.copy(status = null) }
            vm._messages.send(noStreamsMessage(playback))
            if (leaveWhenEmpty) vm.exit()
            return
        }
        val request = PlaybackRequest.Media(
            sessionId = current.sessionId,
            target = playback.target,
            streams = playback.resolution.streams,
            streamIndex = playback.topIndex,
            subtitles = playback.resolution.subtitles,
            startPositionMs = recorder.resumePositionMs(playback.target),
        )
        vm.startMedia(request)
        vm._progress.update { PlayerProgress(request.startPositionMs, request.target.knownDurationMs, 0) }
    }

    /** The session ended: drop any in-flight switch so it can't land in the next session. */
    fun cancel() {
        switchJob?.cancel()
        switchJob = null
        episodesJob?.cancel()
        episodesJob = null
    }
}
