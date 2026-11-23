package com.seastreams.tv.data.setup

import android.util.Log
import com.seastreams.tv.core.database.dao.ProfileDao
import com.seastreams.tv.data.addon.AddonRepository
import com.seastreams.tv.data.addon.DebridAddonState
import com.seastreams.tv.data.addon.UsenetAddonState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Startup check that every profile still has the protected, non-removable TMDB metadata provider.
 * Default addons are otherwise seeded only when a profile is created, so an addon a profile later
 * removed (e.g. OpenSubtitles) is never resurrected.
 *
 * [keepUsenetEntries] adds the built-in `Usenet` entry (bottom of the list) to every profile while Usenet streaming
 * is configured, including profiles created later. Once added, its position and enabled state are the profile's own.
 * [keepDebridEntries] does the same for the built-in `Debrid` entry while Debrid is listed (PRD §14).
 */
@Singleton
class BuiltInAddonGuard @Inject constructor(
    private val addonRepository: AddonRepository,
    private val usenet: UsenetAddonState,
    private val debrid: DebridAddonState,
    private val profiles: ProfileDao,
) {
    suspend fun ensureBuiltIns() = addonRepository.ensureBuiltIns()

    /** Never returns: runs for the process lifetime. */
    suspend fun keepUsenetEntries() = keepEntries(usenet.configured, "Usenet") { addonRepository.ensureUsenet() }

    /** Never returns: runs for the process lifetime. */
    suspend fun keepDebridEntries() = keepEntries(debrid.listed, "Debrid") { addonRepository.ensureDebrid() }

    /** Runs [ensure] whenever [shown] is true and the profile list changes (profiles created later get the entry). */
    private suspend fun keepEntries(shown: Flow<Boolean>, label: String, ensure: suspend () -> Unit) {
        combine(shown, profiles.observeAll()) { on, list -> on to list.map { it.id } }
            .distinctUntilChanged()
            .collectLatest { (on, _) -> if (on) ensureOnce(label, ensure) }
    }

    /**
     * One failed pass (e.g. a profile deleted between the lookup and the insert) is logged and retried on the next
     * change instead of ending the upkeep for the rest of the process.
     */
    private suspend fun ensureOnce(label: String, ensure: suspend () -> Unit) {
        try {
            ensure()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Adding the $label addon entries failed", e)
        }
    }

    private companion object {
        const val TAG = "BuiltInAddonGuard"
    }
}
