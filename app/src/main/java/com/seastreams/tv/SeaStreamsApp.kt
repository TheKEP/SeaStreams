package com.seastreams.tv

import android.app.Application
import android.util.Log
import com.seastreams.tv.core.util.ApplicationScope
import com.seastreams.tv.data.setup.FirstLaunchSeeder
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

    /** Process-lifetime scope (off the main thread) for fire-and-forget startup work. */
    @Inject
    @ApplicationScope
    lateinit var appScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            runCatching { firstLaunchSeeder.get().seedIfNeeded() }
                .onFailure { Log.e("SeaStreamsApp", "Seeding default addons failed", it) }
        }
    }
}
