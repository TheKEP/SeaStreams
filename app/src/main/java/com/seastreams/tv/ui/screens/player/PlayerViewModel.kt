package com.seastreams.tv.ui.screens.player

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.seastreams.tv.core.datastore.AppSettings
import com.seastreams.tv.core.datastore.SettingsDataStore
import com.seastreams.tv.core.util.MILLIS_PER_SECOND
import com.seastreams.tv.domain.model.EpisodeRef
import com.seastreams.tv.domain.model.PlaybackRequest
import com.seastreams.tv.domain.playback.AutoNextPolicy
import com.seastreams.tv.domain.playback.PlayerProgressTracker
import com.seastreams.tv.domain.playback.PlayerStop
import com.seastreams.tv.domain.playback.SubtitleSource
import com.seastreams.tv.player.DisplayHdrSupport
import com.seastreams.tv.player.FallbackReason
import com.seastreams.tv.player.PlayerEngineConfig
import com.seastreams.tv.player.PlayerEvent
import com.seastreams.tv.player.SeaStreamsPlayerManager
import com.seastreams.tv.player.StreamFallbackController
import com.seastreams.tv.player.knownOrZero
import com.seastreams.tv.player.subtitle.ExternalSubtitleLoader
import com.seastreams.tv.player.subtitle.sync.AutoSyncStatus
import com.seastreams.tv.player.subtitle.sync.SyncRate
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import javax.inject.Inject

/**
 * One built-in playback session at a time (PRD §9): drives [SeaStreamsPlayerManager], the Stream
 * Fallback Timer, watch progress, Auto-Play Next Episode, and the HUD's session-only subtitle/audio
 * choices. Trailers skip progress, fallback and auto-next. Episode/stream switching ([PlayerSwitching])
 * happens in place without leaving the player.
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val okHttp: Lazy<OkHttpClient>,
    private val settingsStore: SettingsDataStore,
    internal val progressTracker: PlayerProgressTracker,
    internal val switching: PlayerSwitching,
) : ViewModel() {

    internal val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    internal val _progress = MutableStateFlow(PlayerProgress())
    val progress: StateFlow<PlayerProgress> = _progress.asStateFlow()

    internal val _cue = MutableStateFlow<String?>(null)
    val cue: StateFlow<String?> = _cue.asStateFlow()

    internal val _info = MutableStateFlow<List<InfoRow>>(emptyList())
    val info: StateFlow<List<InfoRow>> = _info.asStateFlow()

    private val _manager = MutableStateFlow<SeaStreamsPlayerManager?>(null)
    val manager: StateFlow<SeaStreamsPlayerManager?> = _manager.asStateFlow()

    private val _exits = Channel<Unit>(Channel.CONFLATED)
    val exits: Flow<Unit> = _exits.receiveAsFlow()

    internal val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    internal var settings = AppSettings()
    private var openedId: Long? = null
    private var sessionJob: Job? = null
    internal var initialSubtitleDone = false
    internal var lastSaveAtPosition = 0L
    private var exiting = false
    private var userExitPending = false
    internal val autoSync = AutoSyncSession()

    /** The subtitle choice made for the playing title; `null` until one is made (reset per title). */
    internal var subtitleChoice: SubtitleChoice? = null

    internal val subtitles = PlayerSubtitles(viewModelScope) {
        ExternalSubtitleLoader(withContext(Dispatchers.IO) { okHttp.get() })
    }

    internal val fallback = StreamFallbackController(
        scope = viewModelScope,
        timeoutMs = { settings.player.fallbackTimeoutSeconds * MILLIS_PER_SECOND },
        failover = { settings.player.autoStreamFailover },
        onAdvance = ::onFallbackAdvance,
        onExhausted = { reason ->
            viewModelScope.launch {
                _messages.send(if (reason == FallbackReason.TIMEOUT) "None of the streams started in time" else "None of the streams would play")
                exit()
            }
        },
        onStopped = { index -> onStreamStopped(index) },
    )

    /** Starts [request] unless it is already the open session. */
    fun open(request: PlaybackRequest) {
        if (openedId == request.sessionId) return
        releaseManager()
        openedId = request.sessionId
        exiting = false
        sessionJob = viewModelScope.launch {
            settings = settingsStore.settings.first()
            val client = withContext(Dispatchers.IO) { okHttp.get() }
            // Cancelled during the initial load: never build (or show) a player for a session already left.
            if (exiting) return@launch
            val audio = SessionAudio.from(settings.audio)
            val config = PlayerEngineConfig.from(settings, audio.toDsp(), DisplayHdrSupport.supportsDolbyVision(context))
            val manager = SeaStreamsPlayerManager(context, client, config)
            _manager.value = manager
            _state.value = PlayerUiState(
                audio = audio,
                dspAvailable = manager.pcmOutput.value,
                cleanSdh = settings.subtitles.cleanSdh,
                subtitleStyle = settings.subtitles.style,
                hdrWhiteDimming = settings.player.hdrWhiteDimming,
            )
            subtitles.renderer.cleanSdh = settings.subtitles.cleanSdh
            launch { manager.events.collect { onEvent(manager, it) } }
            launch { manager.isPlaying.collect { playing -> _state.update { it.copy(playing = playing) } } }
            launch { manager.buffering.collect { buffering -> _state.update { it.copy(buffering = buffering) } } }
            launch { manager.pcmOutput.collect { pcm -> _state.update { it.copy(dspAvailable = pcm) } } }
            launch { manager.dynamicRange.collect { range -> _state.update { it.copy(dynamicRange = range) } } }
            launch { tick(manager) }
            when (request) {
                is PlaybackRequest.Media -> startMedia(request)
                is PlaybackRequest.Trailer -> {
                    fallback.enabled = false
                    _state.update { it.copy(request = request, title = request.title) }
                    runCatching { manager.playTrailer(request.stream) }.onFailure {
                        _messages.send("Trailer couldn't play")
                        exit()
                    }
                }
            }
        }
    }

    /** Plays [request] in the open player (first start, fallback, episode switch, auto-next). */
    internal fun startMedia(request: PlaybackRequest.Media) {
        val manager = _manager.value ?: return
        fallback.enabled = true
        autoSync.stop()
        autoSync.manualOverride = false
        subtitleChoice = null
        subtitles.clear()
        subtitles.setExternal(request.subtitles)
        initialSubtitleDone = false
        lastSaveAtPosition = request.startPositionMs
        _state.update {
            it.copy(
                request = request,
                title = request.title,
                subtitle = episodeSubtitle(request),
                streams = request.streams,
                streamAddons = request.streamAddons,
                streamIndex = request.streamIndex,
                subtitleOptions = subtitles.options(emptyList(), settings.subtitles.preferredLanguage, settings.subtitles.hideNonPreferredLanguages),
                selectedSubtitleId = null,
                subtitleOffsetMs = 0,
                subtitleRate = SyncRate.UNIT,
                subtitleSyncStatus = AutoSyncStatus.Off,
                audioOptions = emptyList(),
                selectedAudioId = null,
                episodes = it.episodes.following(request.target.episode),
            )
        }
        switching.loadLastUsed(this, request)
        playIndex(manager, request.streamIndex, request.startPositionMs)
    }

    internal fun playIndex(manager: SeaStreamsPlayerManager, index: Int, positionMs: Long) {
        val streams = _state.value.streams
        val stream = streams.getOrNull(index) ?: return
        if (_state.value.selectedSubtitle?.source == SubtitleSource.EMBEDDED) {
            _state.update { it.copy(selectedSubtitleId = null) }
            initialSubtitleDone = false
        }
        restartAutoSyncForNewStream()
        // Every new source (first start, fallback advance, stream/episode switch) shows the backdrop until its first frame.
        _state.update { it.onNewStream(index) }
        manager.playStream(stream, positionMs)
        fallback.onStreamStarted(index, streams.size)
    }

    private fun onFallbackAdvance(from: Int, to: Int, reason: FallbackReason) {
        val manager = _manager.value ?: return
        val total = _state.value.streams.size
        val what = if (reason == FallbackReason.TIMEOUT) "didn't start" else "failed"
        _state.update { it.copy(status = "Stream ${from + 1} $what. Trying stream ${to + 1} of $total…") }
        playIndex(manager, to, maxOf(manager.player.currentPosition, 0L))
    }

    private suspend fun onEvent(manager: SeaStreamsPlayerManager, event: PlayerEvent) {
        when (event) {
            PlayerEvent.Ready -> {
                fallback.onReady()
                switching.onStreamReady(this)
                _state.update { it.copy(status = null) }
            }
            PlayerEvent.FirstFrame -> _state.update { it.onFirstFrame() }
            is PlayerEvent.Failed -> if (_state.value.isTrailer) {
                _messages.send("Trailer couldn't play")
                exit()
            } else {
                fallback.onError()
            }
            PlayerEvent.TracksChanged -> refreshTracks(manager)
            PlayerEvent.Ended -> onEnded(manager)
        }
    }

    private suspend fun onEnded(manager: SeaStreamsPlayerManager) {
        val request = _state.value.request ?: return
        val duration = manager.player.duration.knownOrZero()
        progressTracker.saveFinished(request, duration)
        val next = AutoNextPolicy.afterBuiltInStop(request, PlayerStop.ENDED, duration, duration, settings.player.autoPlayNextEpisode)
        if (next) switching.playNextEpisode(this, auto = true) else exit()
    }

    // ---- HUD & transport ------------------------------------------------------------------------

    fun showHud() = _state.update { it.copy(hudVisible = true) }

    fun hideHud() = _state.update { it.copy(hudVisible = false, panel = null) }

    fun openPanel(panel: PlayerPanel) {
        _state.update { it.copy(panel = panel, hudVisible = true) }
        val manager = _manager.value
        if (panel == PlayerPanel.INFO && manager != null) _info.value = buildInfoRows(_state.value, manager)
        if (panel == PlayerPanel.EPISODES) switching.loadEpisodes(this)
    }

    fun closePanel() = _state.update { it.copy(panel = null) }

    internal fun saveNow() {
        val manager = _manager.value ?: return
        val request = _state.value.request ?: return
        val position = manager.player.currentPosition
        val duration = manager.player.duration.knownOrZero()
        viewModelScope.launch { progressTracker.save(request, position, duration) }
    }

    // ---- Back & exit ------------------------------------------------------------------------------

    /** `Back`: closes an open overlay, else hides the HUD, else leaves playback (auto-next when finished). */
    fun back() {
        when (playerBackAction(_state.value)) {
            PlayerBackAction.CLOSE_PANEL -> closePanel()
            PlayerBackAction.HIDE_HUD -> hideHud()
            PlayerBackAction.EXIT -> userExit()
            PlayerBackAction.CANCEL_LOADING -> exit()
        }
    }

    private fun userExit() {
        // A second press while the exit's progress save runs must not save or auto-advance twice.
        if (exiting || userExitPending) return
        val manager = _manager.value
        val request = _state.value.request
        if (manager == null || request == null) {
            exit()
            return
        }
        val position = manager.player.currentPosition
        val duration = manager.player.duration.knownOrZero()
        userExitPending = true
        viewModelScope.launch {
            try {
                progressTracker.save(request, position, duration)
                if (AutoNextPolicy.afterBuiltInStop(request, PlayerStop.USER_EXIT, position, duration, settings.player.autoPlayNextEpisode)) {
                    switching.playNextEpisode(this@PlayerViewModel, auto = true)
                } else {
                    exit()
                }
            } finally {
                userExitPending = false
            }
        }
    }

    /**
     * Leaves playback; progress has already been saved by the caller. The video surface goes first,
     * in this frame, so no HDR/Dolby Vision layer stays on screen while the previous screen returns.
     */
    internal fun exit() {
        if (exiting) return
        exiting = true
        fallback.cancel()
        _state.update { it.copy(videoDetached = true) }
        _manager.value?.stop()
        _exits.trySend(Unit)
    }

    /** The player screen left composition: free the decoder and network connections. */
    fun close() {
        releaseManager()
        openedId = null
        _state.value = PlayerUiState()
        _cue.value = null
    }

    private fun releaseManager() {
        sessionJob?.cancel()
        sessionJob = null
        switching.cancel()
        fallback.cancel()
        autoSync.clear()
        subtitles.clear()
        _manager.value?.release()
        _manager.value = null
    }

    override fun onCleared() = releaseManager()

    fun selectStream(index: Int) = switching.selectStream(this, index)

    fun nextEpisode() = switching.playNextEpisode(this, auto = false)

    fun selectSeason(season: Int) = switching.selectSeason(this, season)

    fun playEpisode(ref: EpisodeRef) = switching.playEpisode(this, ref)

    internal fun currentManager(): SeaStreamsPlayerManager? = _manager.value
}
