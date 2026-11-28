package com.seastreams.tv

import android.app.Application
import android.util.Log
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.seastreams.tv.core.cache.CacheManager
import com.seastreams.tv.core.datastore.SettingsDataStore
import com.seastreams.tv.core.image.buildImageLoader
import com.seastreams.tv.core.util.ApplicationScope
import com.seastreams.tv.data.library.LibraryKeywordBackfill
import com.seastreams.tv.data.setup.BuiltInAddonGuard
import com.seastreams.tv.data.setup.DebugProfileSeeder
import com.seastreams.tv.data.trailer.NewPipeInitializer
import com.seastreams.tv.data.usenet.headers.HeaderVersionUpdater
import com.seastreams.tv.data.setup.ProfileSettingsMigrator
import com.seastreams.tv.data.setup.SourceTogglesMigrator
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class SeaStreamsApp : Application(), SingletonImageLoader.Factory {

    /** Lazy: its graph includes the OkHttp disk cache, which must not be built on the main thread. */
    @Inject
    lateinit var builtInAddonGuard: Lazy<BuiltInAddonGuard>

    /** Debug builds only: a ready-made `Test` profile + developer TMDB key on a fresh install. */
    @Inject
    lateinit var debugProfileSeeder: Lazy<DebugProfileSeeder>

    /** Copies pre-per-profile General Settings into every existing profile; Room is built on IO. */
    @Inject
    lateinit var profileSettingsMigrator: Lazy<ProfileSettingsMigrator>

    /** NewPipeExtractor (in-app trailers) on the shared OkHttp client; lazy for the same reason. */
    @Inject
    lateinit var newPipe: Lazy<NewPipeInitializer>

    /** Removes stray files from `cacheDir`; lazy because it holds the OkHttp disk cache. */
    @Inject
    lateinit var cacheManager: Lazy<CacheManager>

    /** Daily Prowlarr/SABnzbd/… header version check (PRD §12); lazy because it holds the OkHttp client. */
    @Inject
    lateinit var headerVersions: Lazy<HeaderVersionUpdater>

    /** One-time `Enable Usenet` upgrade (ON for installs already set up); Room is built on IO. */
    @Inject
    lateinit var sourceToggles: Lazy<SourceTogglesMigrator>

    /** Library keyword lookups (`Christmas` genre) for movies saved before they were stored; HTTP-backed, so lazy. */
    @Inject
    lateinit var keywordBackfill: Lazy<LibraryKeywordBackfill>

    /** The active profile the keyword backfill follows; DataStore is built on IO. */
    @Inject
    lateinit var settings: Lazy<SettingsDataStore>

    /** Process-lifetime scope (off the main thread) for fire-and-forget startup work. */
    @Inject
    @ApplicationScope
    lateinit var appScope: CoroutineScope

    /** Coil's app-wide loader with fixed 150 MB disk / 25% memory caps (see `ImageLoaderSetup.kt`). */
    override fun newImageLoader(context: PlatformContext): ImageLoader = buildImageLoader(context)

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            // First, so profiles stop reading legacy app-wide values as early as possible.
            runCatching { profileSettingsMigrator.get().migrateIfNeeded() }
                .onFailure { Log.e("SeaStreamsApp", "Migrating per-profile settings failed", it) }
            // NewPipe.init(OkHttpDownloader(okHttpClient)); trailer extraction also ensures it lazily.
            runCatching { newPipe.get().ensureInitialized() }
                .onFailure { Log.e("SeaStreamsApp", "NewPipe init failed", it) }
            // Before the Usenet entry upkeep below reads the toggle.
            runCatching { sourceToggles.get().migrateIfNeeded() }
                .onFailure { Log.e("SeaStreamsApp", "Migrating the source toggles failed", it) }
            // Profiles get their default addons when created; this only repairs a missing TMDB entry.
            runCatching { builtInAddonGuard.get().ensureBuiltIns() }
                .onFailure { Log.e("SeaStreamsApp", "Checking built-in addons failed", it) }
            if (BuildConfig.DEBUG) {
                runCatching { debugProfileSeeder.get().seedIfNeeded() }
                    .onFailure { Log.e("SeaStreamsApp", "Seeding the debug profile failed", it) }
            }
            // Adds the built-in Usenet entry to each profile's addon list once Usenet is configured (runs for the process).
            appScope.launch {
                runCatching { builtInAddonGuard.get().keepUsenetEntries() }
                    .onFailure { Log.e("SeaStreamsApp", "Keeping the Usenet addon entries failed", it) }
            }
            // The same for the built-in Debrid entry while Debrid is on with an enabled provider (PRD §14).
            appScope.launch {
                runCatching { builtInAddonGuard.get().keepDebridEntries() }
                    .onFailure { Log.e("SeaStreamsApp", "Keeping the Debrid addon entries failed", it) }
            }
            // After the migrations and debug seeding above, so the active profile they set is seen. Quietly fills
            // Library keyword ids for the active profile, and again on each switch (once per profile per process).
            appScope.launch {
                runCatching { keywordBackfill.get().followActiveProfile(settings.get().activeProfileId) }
                    .onFailure { Log.e("SeaStreamsApp", "Following the active profile for Library keywords failed", it) }
            }
            // The HTTP and image caches bound themselves (LRU); this only drops files nothing owns.
            runCatching { cacheManager.get().sweepOrphans() }
                .onFailure { Log.e("SeaStreamsApp", "Sweeping orphaned cache files failed", it) }
            // At most once per 24 h; a failed check keeps the last good (or built-in) versions.
            runCatching { headerVersions.get().checkIfDue() }
                .onFailure { Log.e("SeaStreamsApp", "Checking request header versions failed", it) }
        }
    }
}
