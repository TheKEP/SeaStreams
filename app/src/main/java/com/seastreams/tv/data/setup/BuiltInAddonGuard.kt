package com.seastreams.tv.data.setup

import com.seastreams.tv.data.addon.AddonRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Startup check that every profile still has the protected, non-removable TMDB metadata provider.
 * Default addons are otherwise seeded only when a profile is created, so an addon a profile later
 * removed (e.g. OpenSubtitles) is never resurrected.
 */
@Singleton
class BuiltInAddonGuard @Inject constructor(
    private val addonRepository: AddonRepository,
) {
    suspend fun ensureBuiltIns() = addonRepository.ensureBuiltIns()
}
