package com.seastreams.tv.domain.model

import com.seastreams.tv.domain.playback.PlaybackTarget

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
 * One playable part of a YouTube trailer. [dashManifest] is set when the extractor built a DASH
 * manifest for the stream (YouTube serves adaptive streams in short ranges); otherwise [url] is
 * played as a progressive file.
 */
data class TrailerSource(val url: String, val dashManifest: String? = null, val mimeType: String? = null)

/** Adaptive video plus a separate M4A audio track, or a single muxed stream ([audio] `null`). */
data class TrailerStream(val video: TrailerSource, val audio: TrailerSource?, val height: Int)
