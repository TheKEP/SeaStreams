package com.seastreams.tv.data.setup

import com.seastreams.tv.core.datastore.SettingsDataStore
import com.seastreams.tv.data.addon.AddonRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Installs the built-in TMDB metadata provider and the OpenSubtitles addon exactly once per install,
 * so an addon the user later removes is not resurrected on the next launch.
 */
@Singleton
class FirstLaunchSeeder @Inject constructor(
    private val settings: SettingsDataStore,
    private val addonRepository: AddonRepository,
) {
    suspend fun seedIfNeeded() {
        if (settings.isDefaultsSeeded()) return
        addonRepository.seedDefaults()
        settings.markDefaultsSeeded()
    }
}
