package com.seastreams.tv.usenet.session

import com.seastreams.tv.core.datastore.UsenetSettingsStore
import com.seastreams.tv.core.util.IoDispatcher
import com.seastreams.tv.data.usenet.UsenetSourcesRepository
import com.seastreams.tv.domain.usenet.UsenetPerformance
import com.seastreams.tv.usenet.nntp.NntpEngine
import com.seastreams.tv.usenet.nntp.NntpEndpoint
import com.seastreams.tv.usenet.nntp.engineSettings
import dagger.Lazy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** The app engine configured with the current providers and performance, plus the values a session needs. */
class ConfiguredEngine(val engine: NntpEngine, val performance: UsenetPerformance, val endpoints: List<NntpEndpoint>)

/**
 * Applies the enabled providers (decrypted) and the performance profile to the app-wide [NntpEngine]
 * (ARCHITECTURE §10 Entry points) right before it is used: a search warms it up, a session reads through it.
 * The engine keeps unchanged pools, so calling this per use is cheap.
 */
@Singleton
class UsenetEngineSetup @Inject constructor(
    private val repository: UsenetSourcesRepository,
    private val store: UsenetSettingsStore,
    /** Lazy: building the engine opens the disk cache, so it is resolved on IO. */
    private val engine: Lazy<NntpEngine>,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    suspend fun performance(): UsenetPerformance = store.performance.first().clamped()

    suspend fun configure(): ConfiguredEngine {
        val performance = performance()
        val endpoints = repository.enabledEndpoints()
        return withContext(io) {
            val built = engine.get()
            built.configure(endpoints, performance.engineSettings())
            ConfiguredEngine(built, performance, endpoints)
        }
    }

    /** Called when a stream search starts: sign-ins are ready by the time a release is picked. */
    suspend fun warmUp() {
        val configured = configure()
        if (configured.endpoints.isNotEmpty()) withContext(io) { configured.engine.warmUp() }
    }
}
