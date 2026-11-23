package com.seastreams.tv.player.debrid

import com.seastreams.tv.data.debrid.api.DebridException
import com.seastreams.tv.data.debrid.stream.DebridLinkResolver
import com.seastreams.tv.data.debrid.stream.DebridStreamRegistry
import com.seastreams.tv.domain.model.StreamSource
import com.seastreams.tv.player.usenet.PreflightResult
import dagger.Lazy
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves a Debrid library stream's playback link before any player sees it (PRD §14 *Playback*, ARCHITECTURE §12):
 * at auto-play, on a pick, on every failover advance and on Streams-overlay switches. A file deleted from the account,
 * a revoked key, a refused or non-HTTPS link, `Enable Debrid` switched off or an unreachable service all make the
 * stream a [PreflightResult.Skip], so it is passed over silently (or, with failover off, the Streams overlay opens) and
 * ExoPlayer is never prepared with it. The link stays cached in [DebridLinkResolver], so the player's first request
 * reuses it. Other streams pass straight through.
 */
@Singleton
class DebridPreflight @Inject constructor(
    /** Lazy: its HTTP stack and Room-backed repository are resolved off the main thread. */
    private val links: Lazy<DebridLinkResolver>,
) {
    suspend fun check(stream: StreamSource): PreflightResult {
        val token = DebridStreamRegistry.tokenOf(stream.url) ?: return PreflightResult.Playable
        return try {
            links.get().resolve(token)
            PreflightResult.Playable
        } catch (e: DebridException) {
            PreflightResult.Skip(e.message ?: REFUSED)
        } catch (e: IOException) {
            // Network trouble skips this stream now; nothing is remembered.
            PreflightResult.Skip(e.message ?: REFUSED)
        }
    }

    private companion object {
        const val REFUSED = "That Debrid file can't be played"
    }
}
