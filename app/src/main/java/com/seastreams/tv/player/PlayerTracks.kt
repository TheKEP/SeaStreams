package com.seastreams.tv.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import com.seastreams.tv.domain.playback.AudioOption
import com.seastreams.tv.domain.playback.SubtitleOption
import com.seastreams.tv.domain.playback.SubtitleSource
import java.util.Locale

/** Reads ExoPlayer's current tracks into SeaStreams options and applies track choices. */
object PlayerTracks {

    fun audio(tracks: Tracks): List<AudioOption> = tracksOf(tracks, C.TRACK_TYPE_AUDIO).map { (id, format) ->
        AudioOption(id, audioLabel(format), format.language)
    }

    fun embeddedSubtitles(tracks: Tracks): List<SubtitleOption> = tracksOf(tracks, C.TRACK_TYPE_TEXT).map { (id, format) ->
        SubtitleOption(id, textLabel(format), format.language, SubtitleSource.EMBEDDED)
    }

    /** Id of the playing audio track, if any. */
    fun selectedAudioId(tracks: Tracks): String? = selectedId(tracks, C.TRACK_TYPE_AUDIO)

    /** Whether any track reports Dolby Vision Profile 7 (the Info overlay notes the HDR10 conversion). */
    fun hasDolbyVisionProfile7(tracks: Tracks): Boolean = tracksOf(tracks, C.TRACK_TYPE_VIDEO).any { (_, format) ->
        DolbyVisionCompat.isProfile7(format.sampleMimeType, format.codecs)
    }

    fun selectAudio(player: Player, id: String) = override(player, C.TRACK_TYPE_AUDIO, id)

    /** Shows embedded text track [id], or turns embedded subtitles off when `null`. */
    fun selectEmbeddedText(player: Player, id: String?) {
        if (id == null) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
        } else {
            override(player, C.TRACK_TYPE_TEXT, id)
        }
    }

    private fun override(player: Player, type: Int, id: String) {
        val (groupIndex, trackIndex) = parseId(id) ?: return
        val group = player.currentTracks.groups.getOrNull(groupIndex)?.takeIf { it.type == type } ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
            .build()
    }

    private fun tracksOf(tracks: Tracks, type: Int): List<Pair<String, Format>> =
        tracks.groups.withIndex().filter { it.value.type == type }.flatMap { (groupIndex, group) ->
            (0 until group.length).filter { group.isTrackSupported(it) }.map { "$groupIndex:$it" to group.getTrackFormat(it) }
        }

    private fun selectedId(tracks: Tracks, type: Int): String? {
        tracks.groups.forEachIndexed { groupIndex, group ->
            if (group.type != type) return@forEachIndexed
            for (i in 0 until group.length) if (group.isTrackSelected(i)) return "$groupIndex:$i"
        }
        return null
    }

    private fun parseId(id: String): Pair<Int, Int>? {
        val parts = id.split(':')
        val group = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val track = parts.getOrNull(1)?.toIntOrNull() ?: return null
        return group to track
    }

    fun languageName(code: String?): String? {
        val tag = code?.takeIf { it.isNotBlank() && it != C.LANGUAGE_UNDETERMINED } ?: return null
        return Locale.forLanguageTag(tag).getDisplayLanguage(Locale.US).ifBlank { tag }
    }

    /** `English · 5.1 · E-AC-3`. */
    fun audioLabel(format: Format): String = listOfNotNull(
        format.label ?: languageName(format.language) ?: "Unknown language",
        channelLayout(format.channelCount),
        codecName(format.sampleMimeType),
    ).distinct().joinToString(" · ")

    private fun textLabel(format: Format): String = listOfNotNull(
        format.label ?: languageName(format.language) ?: "Unknown language",
        if (format.selectionFlags and C.SELECTION_FLAG_FORCED != 0) "Forced" else null,
    ).distinct().joinToString(" · ")

    fun channelLayout(channels: Int): String? = when (channels) {
        Format.NO_VALUE, 0 -> null
        1 -> "Mono"
        2 -> "Stereo"
        6 -> "5.1"
        8 -> "7.1"
        else -> "$channels ch"
    }

    fun codecName(mime: String?): String? = when (mime) {
        null -> null
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_AC3 -> "AC-3"
        MimeTypes.AUDIO_E_AC3 -> "E-AC-3"
        MimeTypes.AUDIO_E_AC3_JOC -> "E-AC-3 Atmos"
        MimeTypes.AUDIO_TRUEHD -> "TrueHD"
        MimeTypes.AUDIO_DTS -> "DTS"
        MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
        MimeTypes.AUDIO_OPUS -> "Opus"
        MimeTypes.AUDIO_FLAC -> "FLAC"
        MimeTypes.AUDIO_MPEG -> "MP3"
        MimeTypes.VIDEO_H264 -> "H.264"
        MimeTypes.VIDEO_H265 -> "HEVC"
        MimeTypes.VIDEO_AV1 -> "AV1"
        MimeTypes.VIDEO_VP9 -> "VP9"
        MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
        else -> mime.substringAfter('/').uppercase(Locale.ROOT)
    }
}
