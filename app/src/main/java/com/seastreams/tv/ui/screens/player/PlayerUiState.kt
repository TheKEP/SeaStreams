package com.seastreams.tv.ui.screens.player

import com.seastreams.tv.core.datastore.AudioSettings
import com.seastreams.tv.core.datastore.DialogueBoost
import com.seastreams.tv.core.datastore.SubtitleStyle
import com.seastreams.tv.core.util.MILLIS_PER_SECOND
import com.seastreams.tv.domain.model.Episode
import com.seastreams.tv.domain.model.EpisodeRef
import com.seastreams.tv.domain.model.PlaybackRequest
import com.seastreams.tv.domain.model.SeasonInfo
import com.seastreams.tv.domain.model.StreamAddon
import com.seastreams.tv.domain.model.StreamSource
import com.seastreams.tv.domain.playback.AudioOption
import com.seastreams.tv.domain.playback.SubtitleOption
import com.seastreams.tv.domain.playback.SubtitleSource
import com.seastreams.tv.player.audio.AudioDspConfig
import com.seastreams.tv.ui.components.MetadataFormat

/** The in-player overlays opened from the bottom bar. */
enum class PlayerPanel { SUBTITLES, AUDIO, STREAMS, EPISODES, INFO }

/** Session-only audio DSP (PRD §9): starts from General Settings, never written back. */
data class SessionAudio(val dialogueBoost: DialogueBoost, val nightMode: Boolean, val delayMs: Int) {
    fun toDsp() = AudioDspConfig(dialogueBoost.gainDb, nightMode, delayMs)

    companion object {
        fun from(settings: AudioSettings) = SessionAudio(settings.dialogueBoost, settings.nightMode, settings.audioDelayMs)
    }
}

/** The in-player season/episode browser. */
data class EpisodeBrowser(
    val seasons: List<SeasonInfo> = emptyList(),
    val season: Int = 1,
    val episodes: List<Episode> = emptyList(),
    val loading: Boolean = false,
    val playing: EpisodeRef? = null,
) {
    /**
     * The browser once [episode] starts playing. When it is in another season than the one loaded
     * (e.g. a shuffled Next Episode jumped seasons), the loaded list is dropped so the overlay opens
     * on the playing episode's season.
     */
    fun following(episode: EpisodeRef?): EpisodeBrowser = when {
        episode == null || episode.season == season -> copy(playing = episode)
        else -> copy(season = episode.season, episodes = emptyList(), loading = false, playing = episode)
    }
}

data class PlayerUiState(
    val request: PlaybackRequest? = null,
    val title: String = "",
    /** `S1:E2 · Pilot` for episodes. */
    val subtitle: String? = null,
    val playing: Boolean = false,
    val buffering: Boolean = true,
    /** The current stream has rendered its first frame ([onFirstFrame]); reset by [onNewStream]. */
    val started: Boolean = false,
    val hudVisible: Boolean = false,
    val panel: PlayerPanel? = null,
    val streams: List<StreamSource> = emptyList(),
    /** The `Streams` overlay's addon cards, in addon order; empty for trailers. */
    val streamAddons: List<StreamAddon> = emptyList(),
    val streamIndex: Int = 0,
    /** This movie/episode's `LAST USED` stream in [streams] (badge + overlay focus); `null` if none. */
    val lastUsedIndex: Int? = null,
    val subtitleOptions: List<SubtitleOption> = emptyList(),
    val selectedSubtitleId: String? = null,
    val subtitleOffsetMs: Long = 0,
    val cleanSdh: Boolean = false,
    val subtitleStyle: SubtitleStyle = SubtitleStyle(),
    val audioOptions: List<AudioOption> = emptyList(),
    val selectedAudioId: String? = null,
    val audio: SessionAudio = SessionAudio(DialogueBoost.OFF, nightMode = false, delayMs = 0),
    val dspAvailable: Boolean = false,
    val hdrWhiteDimming: Boolean = false,
    val episodes: EpisodeBrowser = EpisodeBrowser(),
    /** Short status, e.g. `Stream 1 didn't start. Trying stream 2…`. */
    val status: String? = null,
) {
    val isTrailer: Boolean get() = request is PlaybackRequest.Trailer

    /** Initial-loading backdrop vs. rebuffering logo (PRD §9). */
    val loadingVisual: LoadingVisual get() = loadingVisual(isTrailer, started, buffering)

    /** A movie/episode whose current stream hasn't drawn a frame yet: only `Back` and media keys act. */
    val initialLoading: Boolean get() = request is PlaybackRequest.Media && !started

    val isEpisode: Boolean get() = (request as? PlaybackRequest.Media)?.target?.episode != null

    val selectedSubtitle: SubtitleOption? get() = subtitleOptions.firstOrNull { it.id == selectedSubtitleId }

    /** The `- 0.0s +` sync control shows only for an active external (addon) track. */
    val externalSubtitleActive: Boolean get() = selectedSubtitle?.source == SubtitleSource.EXTERNAL
}

/** Position values, published apart from [PlayerUiState] so the twice-a-second tick stays cheap. */
data class PlayerProgress(val positionMs: Long = 0, val durationMs: Long = 0, val bufferedMs: Long = 0)

/** One row of the Info overlay. */
data class InfoRow(val label: String, val value: String)

/** `S1:E2 · Pilot`. */
fun episodeSubtitle(request: PlaybackRequest.Media): String? {
    val ref = request.target.episode ?: return null
    val badge = MetadataFormat.episodeBadge(ref.season, ref.episode) ?: return null
    return listOfNotNull(badge, request.target.episodeTitle).joinToString(" · ")
}

/** `1:02:03` / `42:05`. */
fun formatPlaybackTime(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0) / MILLIS_PER_SECOND).toInt()
    val hours = totalSeconds / SECONDS_PER_HOUR
    val minutes = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

private const val SECONDS_PER_HOUR = 3_600
private const val SECONDS_PER_MINUTE = 60
