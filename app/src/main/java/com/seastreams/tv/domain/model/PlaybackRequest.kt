package com.seastreams.tv.domain.model

import com.seastreams.tv.domain.playback.PlaybackTarget
import com.seastreams.tv.domain.playback.ShuffleMode

/**
 * What the built-in player is asked to play. [sessionId] tells two requests for the same title apart,
 * so the player restarts when the same title is played again.
 */
sealed interface PlaybackRequest {
    val sessionId: Long
    val title: String

    /** A movie or episode from Stremio addons, with the full resolved stream list for fallback/switching. */
    data class Media(
        override val sessionId: Long,
        val target: PlaybackTarget,
        val streams: List<StreamSource>,
        val streamIndex: Int,
        val subtitles: List<SubtitleTrack>,
        val startPositionMs: Long,
    ) : PlaybackRequest {
        override val title: String get() = target.details.media.title

        /** Loading-screen branding, derived from [target] so stream/episode switches and auto-next always match. */
        val artwork: PlaybackArtwork get() = target.details.media.playbackArtwork()

        /**
         * Active shuffle mode, derived from [target] so it survives stream switches and is carried
         * through `Next Episode` / Auto-Play Next Episode; a manual `Episodes` overlay pick resets it to `None`.
         */
        val shuffle: ShuffleMode get() = target.shuffle.mode
    }

    /**
     * A Details-screen trailer: always full-screen with sound, no watch progress, no stream fallback
     * and no auto-next (PRD §9 In-App Trailer Playback).
     */
    data class Trailer(
        override val sessionId: Long,
        override val title: String,
        val stream: TrailerStream,
    ) : PlaybackRequest
}

/**
 * Movie/show loading-screen branding (PRD §9 Player Loading & Buffering UI): the full-screen
 * [backdropUrl] with [logoUrl] centred, or [title] as text when there is no logo. A blank [title] with
 * no logo shows the backdrop alone (a Continue Watching card before its title details have loaded).
 */
data class PlaybackArtwork(val backdropUrl: String?, val logoUrl: String?, val title: String)

fun MediaItem.playbackArtwork(): PlaybackArtwork = PlaybackArtwork(heroBackdropUrl ?: backdropUrl, logoUrl, title)

/**
 * One playable part of a YouTube trailer. [dashManifest] is set when the extractor built a DASH
 * manifest for the stream (YouTube serves adaptive streams in short ranges); otherwise [url] is
 * played as a progressive file.
 */
data class TrailerSource(val url: String, val dashManifest: String? = null, val mimeType: String? = null)

/** Adaptive video plus a separate M4A audio track, or a single muxed stream ([audio] `null`). */
data class TrailerStream(val video: TrailerSource, val audio: TrailerSource?, val height: Int)
