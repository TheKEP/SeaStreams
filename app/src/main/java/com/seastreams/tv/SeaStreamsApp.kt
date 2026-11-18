package com.seastreams.tv

import android.app.Application
import android.util.Log
import com.seastreams.tv.core.util.ApplicationScope
import com.seastreams.tv.data.setup.DebugProfileSeeder
import com.seastreams.tv.data.trailer.NewPipeInitializer
import com.seastreams.tv.data.setup.FirstLaunchSeeder
import com.seastreams.tv.data.setup.ProfileSettingsMigrator
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class SeaStreamsApp : Application() {

    /** Lazy: its graph includes the OkHttp disk cache, which must not be built on the main thread. */
    @Inject
    lateinit var firstLaunchSeeder: Lazy<FirstLaunchSeeder>

    /** Debug builds only: a ready-made `Test` profile + developer TMDB key on a fresh install. */
    @Inject
    lateinit var debugProfileSeeder: Lazy<DebugProfileSeeder>

    /** Copies pre-per-profile General Settings into every existing profile; Room is built on IO. */
    @Inject
    lateinit var profileSettingsMigrator: Lazy<ProfileSettingsMigrator>

    /** NewPipeExtractor (in-app trailers) on the shared OkHttp client; lazy for the same reason. */
    @Inject
    lateinit var newPipe: Lazy<NewPipeInitializer>

    /** Process-lifetime scope (off the main thread) for fire-and-forget startup work. */
    @Inject
    @ApplicationScope
    lateinit var appScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            // First, so profiles stop reading legacy app-wide values as early as possible.
            runCatching { profileSettingsMigrator.get().migrateIfNeeded() }
                .onFailure { Log.e("SeaStreamsApp", "Migrating per-profile settings failed", it) }
            // NewPipe.init(OkHttpDownloader(okHttpClient)); trailer extraction also ensures it lazily.
            runCatching { newPipe.get().ensureInitialized() }
                .onFailure { Log.e("SeaStreamsApp", "NewPipe init failed", it) }
            runCatching { firstLaunchSeeder.get().seedIfNeeded() }
                .onFailure { Log.e("SeaStreamsApp", "Seeding default addons failed", it) }
            if (BuildConfig.DEBUG) {
                runCatching { debugProfileSeeder.get().seedIfNeeded() }
                    .onFailure { Log.e("SeaStreamsApp", "Seeding the debug profile failed", it) }
            }
        }
    }
}
