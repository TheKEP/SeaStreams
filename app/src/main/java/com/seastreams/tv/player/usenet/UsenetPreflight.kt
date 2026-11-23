package com.seastreams.tv.player.usenet

import com.seastreams.tv.core.datastore.UsenetSettingsStore
import com.seastreams.tv.core.util.IoDispatcher
import com.seastreams.tv.domain.model.StreamSource
import com.seastreams.tv.usenet.archive.ArchiveRefusedException
import com.seastreams.tv.usenet.session.UsenetGrab
import com.seastreams.tv.usenet.session.UsenetSessionRegistry
import com.seastreams.tv.usenet.session.UsenetStreamRegistry
import com.seastreams.tv.usenet.verify.ReleaseHealth
import com.seastreams.tv.usenet.verify.ReleaseHealthCache
import dagger.Lazy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Whether a stream may be handed to a player. */
sealed interface PreflightResult {
    data object Playable : PreflightResult

    /** The release can't be opened now ([reason] is worded for a toast); the player is never given it. */
    data class Skip(val reason: String) : PreflightResult
}

/**
 * Opens a Usenet release before any player sees it (PRD §12 Playback, ARCHITECTURE §10): grab the NZB (the only
 * `t=get`, spent on Play, never during search), parse it, assemble the media, run the census budget. A release
 * that can't stream (7z, compressed or encrypted RAR, missing articles, a non-NZB) is skipped silently, so ExoPlayer
 * is never prepared with it and no playback error, re-init or audio-focus change happens. Lasting refusals are
 * remembered in [ReleaseHealthCache]; a release already known bad is skipped without being opened again.
 * The opened session stays in the [UsenetSessionRegistry], so the player's first read reuses it. Other streams
 * pass straight through.
 */
@Singleton
class UsenetPreflight @Inject constructor(
    /** Lazy: the session graph (engine, caches) is resolved on IO. */
    private val sessions: Lazy<UsenetSessionRegistry>,
    private val health: ReleaseHealthCache,
    @IoDispatcher private val io: CoroutineDispatcher,
    private val settings: UsenetSettingsStore,
    private val grabs: UsenetStreamRegistry,
) {
    suspend fun check(stream: StreamSource): PreflightResult {
        val token = UsenetStreamRegistry.tokenOf(stream.url) ?: return PreflightResult.Playable
        // `Enable Usenet` OFF: a LAST USED / overlay pick that needs the user's providers is skipped (an addon's own
        // `servers` still play, PRD §12).
        if (!settings.enabled.first() && needsUserProviders(grabs.get(token))) return PreflightResult.Skip(USENET_OFF)
        when (val known = health.get(token)) {
            is ReleaseHealth.Broken -> return PreflightResult.Skip(known.reason)
            is ReleaseHealth.NotStreamable -> return PreflightResult.Skip(known.reason)
            ReleaseHealth.Healthy, null -> Unit
        }
        return try {
            runInterruptible(io) {
                val registry = sessions.get()
                registry.acquire(token)
                registry.release(token)
            }
            PreflightResult.Playable
        } catch (e: ArchiveRefusedException) {
            // The opener records refusals; this keeps the guarantee even for a refusal raised outside it.
            if (health.get(token) == null) health.record(token, ReleaseHealth.NotStreamable(e.message.orEmpty()))
            PreflightResult.Skip(e.message ?: REFUSED)
        } catch (e: IOException) {
            // Other unplayable verdicts are recorded by the opener / census; network trouble is not remembered.
            PreflightResult.Skip(e.message ?: REFUSED)
        }
    }

    private fun needsUserProviders(grab: UsenetGrab?): Boolean = grab !is UsenetGrab.FromAddon || grab.servers.isEmpty()

    private companion object {
        const val USENET_OFF = "Usenet is switched off in Settings"
        const val REFUSED = "That Usenet release can't be played"
    }
}
