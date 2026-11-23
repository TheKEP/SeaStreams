package com.seastreams.tv.data.setup

import com.seastreams.tv.core.database.dao.ProfileDao
import com.seastreams.tv.data.addon.AddonRepository
import com.seastreams.tv.data.addon.UsenetAddonState
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
 */
@Singleton
class BuiltInAddonGuard @Inject constructor(
    private val addonRepository: AddonRepository,
    private val usenet: UsenetAddonState,
    private val profiles: ProfileDao,
) {
    suspend fun ensureBuiltIns() = addonRepository.ensureBuiltIns()

    /** Never returns: runs for the process lifetime. */
    suspend fun keepUsenetEntries() {
        combine(usenet.configured, profiles.observeAll()) { configured, list -> configured to list.map { it.id } }
            .distinctUntilChanged()
            .collectLatest { (configured, _) -> if (configured) addonRepository.ensureUsenet() }
    }
}
