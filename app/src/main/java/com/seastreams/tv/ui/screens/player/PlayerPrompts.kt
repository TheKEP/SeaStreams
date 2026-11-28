package com.seastreams.tv.ui.screens.player

import androidx.lifecycle.viewModelScope
import androidx.media3.common.MimeTypes
import com.seastreams.tv.core.datastore.SkipPromptMode
import com.seastreams.tv.data.skip.SkipSegmentRepository
import com.seastreams.tv.domain.model.PlaybackRequest
import com.seastreams.tv.domain.playback.AutoNextPolicy
import com.seastreams.tv.domain.playback.skip.ChapterSegmentClassifier
import com.seastreams.tv.domain.playback.skip.MediaChapter
import com.seastreams.tv.domain.playback.skip.PlayerPromptPolicy
import com.seastreams.tv.domain.playback.skip.Prompt
import com.seastreams.tv.domain.playback.skip.PromptKind
import com.seastreams.tv.domain.playback.skip.PromptPresenter
import com.seastreams.tv.domain.playback.skip.PromptStep
import com.seastreams.tv.domain.playback.skip.SkipSegment
import com.seastreams.tv.domain.playback.skip.SkipSegmentMerger
import com.seastreams.tv.player.SeaStreamsPlayerManager
import com.seastreams.tv.player.knownOrZero
import com.seastreams.tv.ui.components.MetadataFormat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The prompt on screen: its button text, the next episode's badge, and the countdown's whole seconds left. */
data class PlayerPromptUi(val kind: PromptKind, val detail: String? = null, val countdownSeconds: Int? = null) {
    val label: String get() = kind.label
}

/**
 * The built-in player's Skip intro / recap / credits / to post-credits and Next episode prompts (PRD §9 *Skip &
 * Next Episode Prompts*): loads each title's skip times (IntroDB) and each Matroska stream's chapters, re-evaluates
 * [PlayerPromptPolicy] from the twice-a-second tick, runs auto-skip and the credits countdown, and publishes
 * [PlayerUiState.prompt]. Never for trailers, a stream not handed over yet, the initial load, a failed stream, an
 * episode switch in flight, or while a right-hand panel is open.
 */
class PlayerPrompts @Inject constructor(private val repository: SkipSegmentRepository) {
    private var onlineJob: Job? = null
    private var chapterJob: Job? = null
    private var online: List<SkipSegment> = emptyList()
    private var chapters: List<MediaChapter> = emptyList()
    private var probedGeneration: Int? = null
    private var merged: Pair<Long, List<SkipSegment>>? = null
    private val presenter = PromptPresenter()

    /** A new movie/episode starts in the open player: forget the previous one's times, look up this one's. */
    fun onNewTitle(vm: PlayerViewModel, request: PlaybackRequest.Media) {
        reset()
        onNewStream()
        val player = vm.settings.player
        val anyPrompt = player.skipIntroRecap != SkipPromptMode.OFF || player.skipCredits != SkipPromptMode.OFF || player.nextEpisodePrompt
        if (!anyPrompt || !player.onlineSkipTimes) return
        onlineJob = vm.viewModelScope.launch {
            val segments = repository.online(request.target.imdbId, request.target.episode)
            if (vm._state.value.request === request) {
                online = segments
                merged = null
            }
        }
    }

    /** Another stream of the same title (fallback, Streams overlay): its chapters are read again. */
    fun onNewStream() {
        chapterJob?.cancel()
        chapters = emptyList()
        probedGeneration = null
        merged = null
    }

    /** The stream's first frame: read its chapters once when it is Matroska/WebM. */
    fun onFirstFrame(vm: PlayerViewModel, manager: SeaStreamsPlayerManager) {
        val generation = manager.generation
        if (probedGeneration == generation) return
        probedGeneration = generation
        if (manager.player.videoFormat?.containerMimeType !in MATROSKA) return
        val state = vm._state.value
        val stream = state.streams.getOrNull(state.streamIndex) ?: return
        chapterJob?.cancel()
        chapterJob = vm.viewModelScope.launch {
            val read = repository.chapters(stream)
            if (manager.generation == generation) {
                chapters = read
                merged = null
            }
        }
    }

    /** From the tick: picks the prompt for the current position and acts on auto-skip / a finished countdown. */
    fun update(vm: PlayerViewModel, manager: SeaStreamsPlayerManager, nowMs: Long) {
        val state = vm._state.value
        val media = state.request as? PlaybackRequest.Media
        val handedOver = vm.progressBinding.savable(state.request) != null
        if (media == null || !handedOver || state.initialLoading || state.streamFailed || vm.switching.inFlight) return hide(vm)
        val duration = manager.player.duration.knownOrZero()
        val isEpisode = media.target.episode != null
        val pick = if (isEpisode) vm.switching.previewNext(media.target) else null
        val prompt = PlayerPromptPolicy.prompt(
            manager.player.currentPosition, duration, segmentsFor(duration, isMovie = !isEpisode), isEpisode, pick != null, vm.settings.player,
        )
        val countdownAllowed = AutoNextPolicy.afterCreditsCountdown(media, vm.settings.player.autoPlayNextEpisode)
        when (val step = presenter.step(prompt, state.hudVisible, state.playing, state.panel != null, countdownAllowed, nowMs)) {
            is PromptStep.Act -> perform(vm, step.prompt)
            is PromptStep.Show -> {
                val ui = step.prompt?.let { p ->
                    val detail = if (p.kind == PromptKind.NEXT_EPISODE) pick?.episode?.let { MetadataFormat.episodeBadge(it.season, it.episode) } else null
                    PlayerPromptUi(p.kind, detail, step.countdownMs?.let { ((it + MILLIS - 1) / MILLIS).toInt() })
                }
                if (state.prompt != ui) vm._state.update { it.copy(prompt = ui) }
            }
        }
    }

    /** `OK` on the prompt. */
    fun act(vm: PlayerViewModel) {
        val prompt = presenter.current ?: return
        perform(vm, prompt.copy(auto = false))
    }

    /**
     * `Back` while a prompt shows (PRD §9 Back tiers): hides it for the rest of its stretch and stops a running
     * countdown; playback continues. `PlayerScreen` then hands focus back to the root or `Play / Pause`.
     */
    fun dismiss(vm: PlayerViewModel) {
        presenter.dismiss()
        vm._state.update { it.copy(prompt = null) }
    }

    /** Any remote key: a running Next episode countdown becomes a plain button. */
    fun onUserKey() = presenter.cancelCountdown()

    private fun perform(vm: PlayerViewModel, prompt: Prompt) {
        presenter.hide()
        vm._state.update { it.copy(prompt = null) }
        val target = prompt.targetMs
        if (prompt.kind == PromptKind.NEXT_EPISODE || target == null) {
            vm.switching.playNextEpisode(vm, auto = prompt.auto, finished = true)
            return
        }
        vm.seekTo(target)
        if (!prompt.auto) return
        val text = "Skipped ${prompt.kind.skippedWhat}"
        vm._state.update { it.copy(status = text) }
        vm.viewModelScope.launch {
            delay(STATUS_MS)
            vm._state.update { if (it.status == text) it.copy(status = null) else it }
        }
    }

    private fun segmentsFor(durationMs: Long, isMovie: Boolean): List<SkipSegment> {
        merged?.takeIf { it.first == durationMs }?.let { return it.second }
        val fromChapters = ChapterSegmentClassifier.classify(chapters, durationMs)
        return SkipSegmentMerger.merge(fromChapters, online, isMovie, durationMs).also { merged = durationMs to it }
    }

    private fun hide(vm: PlayerViewModel) {
        presenter.hide()
        if (vm._state.value.prompt != null) vm._state.update { it.copy(prompt = null) }
    }

    private fun reset() {
        onlineJob?.cancel()
        online = emptyList()
        presenter.reset()
    }

    /** The session ended. */
    fun cancel() {
        reset()
        onNewStream()
    }

    private val PromptKind.skippedWhat: String
        get() = when (this) {
            PromptKind.SKIP_INTRO -> "intro"
            PromptKind.SKIP_RECAP -> "recap"
            PromptKind.SKIP_TO_POST_CREDITS, PromptKind.SKIP_CREDITS -> "credits"
            PromptKind.NEXT_EPISODE -> "to the next episode"
        }

    private companion object {
        val MATROSKA = setOf(MimeTypes.VIDEO_MATROSKA, MimeTypes.VIDEO_WEBM)
        const val MILLIS = 1_000L
        const val STATUS_MS = 2_500L
    }
}

/** `OK` on the Skip / Next episode prompt. */
fun PlayerViewModel.actOnPrompt() = prompts.act(this)

/** Any remote key press in the player (cancels the Next episode countdown). */
fun PlayerViewModel.onUserKey() = prompts.onUserKey()

/** Saves the playing request as watched (the Next episode prompt leaves before the natural end). */
internal fun PlayerViewModel.saveFinishedNow() {
    val manager = currentManager() ?: return
    val request = progressBinding.savable(_state.value.request) ?: return
    val duration = manager.player.duration.knownOrZero()
    viewModelScope.launch { progressTracker.saveFinished(request, duration) }
}
