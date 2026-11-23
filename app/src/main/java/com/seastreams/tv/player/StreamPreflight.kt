package com.seastreams.tv.player

import com.seastreams.tv.data.debrid.stream.DebridStreamRegistry
import com.seastreams.tv.domain.model.StreamSource
import com.seastreams.tv.player.debrid.DebridPreflight
import com.seastreams.tv.player.usenet.PreflightResult
import com.seastreams.tv.player.usenet.UsenetPreflight
import com.seastreams.tv.usenet.session.UsenetStreamRegistry
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The pre-player check every built-in source stream goes through (PRD §12, §14): Usenet releases are opened by
 * [UsenetPreflight], Debrid library files get their playback link from [DebridPreflight]. Used by
 * `PlaybackStartGate.firstPlayable` (auto-play, picks) and the player's `PlayerStreamGate` (failover advances,
 * Streams-overlay picks, episode switches). Stremio streams are not [handles]d: the player's fallback timer judges them.
 */
@Singleton
class StreamPreflight @Inject constructor(private val usenet: UsenetPreflight, private val debrid: DebridPreflight) {

    /** Whether [url] must pass [check] before a player may be given it. */
    fun handles(url: String): Boolean = UsenetStreamRegistry.isUsenet(url) || DebridStreamRegistry.isDebrid(url)

    suspend fun check(stream: StreamSource): PreflightResult = when {
        UsenetStreamRegistry.isUsenet(stream.url) -> usenet.check(stream)
        DebridStreamRegistry.isDebrid(stream.url) -> debrid.check(stream)
        else -> PreflightResult.Playable
    }
}
