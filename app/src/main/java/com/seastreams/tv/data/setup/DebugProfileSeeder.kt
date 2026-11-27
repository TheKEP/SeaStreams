package com.seastreams.tv.data.setup

import com.seastreams.tv.BuildConfig
import com.seastreams.tv.core.datastore.SettingsDataStore
import com.seastreams.tv.core.util.redacted
import com.seastreams.tv.data.profile.ProfileDraft
import com.seastreams.tv.data.profile.ProfileRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Whether to seed a developer profile, and the TMDB key (from `local.properties`) to seed it with. */
data class DebugSeedConfig(val enabled: Boolean, val tmdbApiKey: String) {
    override fun toString(): String = "DebugSeedConfig(enabled=$enabled, tmdbApiKey=${redacted(tmdbApiKey)})"
}

@Module
@InstallIn(SingletonComponent::class)
object DebugSeedModule {
    @Provides
    fun provideConfig(): DebugSeedConfig = DebugSeedConfig(enabled = BuildConfig.DEBUG, tmdbApiKey = BuildConfig.TMDB_API_KEY)
}

/**
 * Debug builds only: when no profile exists yet, saves the developer's TMDB key (unless one is
 * already saved) and creates a default [PROFILE_NAME] profile, so a fresh emulator install skips
 * onboarding. Does nothing in release builds or when `TMDB_API_KEY` is absent from `local.properties`.
 * The key is saved without the `/configuration` round-trip: it is the developer's own key.
 */
@Singleton
class DebugProfileSeeder @Inject constructor(
    private val config: DebugSeedConfig,
    private val settings: SettingsDataStore,
    private val profiles: ProfileRepository,
) {
    /** @return whether the profile was seeded. */
    suspend fun seedIfNeeded(): Boolean {
        val key = config.tmdbApiKey.trim()
        if (!config.enabled || key.isEmpty()) return false
        if (profiles.observeProfiles().first().isNotEmpty()) return false
        if (settings.currentApiKey().isNullOrBlank()) settings.setTmdbApiKey(key)
        profiles.create(ProfileDraft(name = PROFILE_NAME))
        return true
    }

    companion object {
        const val PROFILE_NAME = "Test"
    }
}
