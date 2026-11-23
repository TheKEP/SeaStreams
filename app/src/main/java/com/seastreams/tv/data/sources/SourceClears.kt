package com.seastreams.tv.data.sources

import com.seastreams.tv.core.datastore.DebridSettingsStore
import com.seastreams.tv.core.datastore.UsenetSettingsStore
import com.seastreams.tv.core.util.ApplicationScope
import com.seastreams.tv.core.util.IoDispatcher
import com.seastreams.tv.data.debrid.DebridSourcesRepository
import com.seastreams.tv.data.debrid.auth.DeviceAuthSessions
import com.seastreams.tv.data.usenet.UsenetSourcesRepository
import com.seastreams.tv.domain.usenet.UsenetPerformance
import com.seastreams.tv.usenet.nntp.NntpEngine
import com.seastreams.tv.usenet.nntp.engineSettings
import dagger.Lazy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One source's `Clear … settings` with a 5-second Undo (PRD §11 `Sources`, ARCHITECTURE §10): [schedule] starts the
 * window and nothing is deleted until it passes; [undo] cancels it. The window lives only in memory, so leaving the
 * app (process death) inside it deletes nothing. [deadline] is non-null while the clear can still be undone.
 */
open class PendingSourceClear(
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val clear: suspend () -> Unit,
) {
    private val lock = Mutex()
    private var job: Job? = null
    private val _deadline = MutableStateFlow<Long?>(null)

    /** When the clear commits (epoch ms), or `null` when none is pending. */
    val deadline: StateFlow<Long?> = _deadline.asStateFlow()

    /** Starts the Undo window; a second press while one is pending keeps the first deadline. */
    suspend fun schedule() = lock.withLock {
        if (_deadline.value != null) return@withLock
        val deadline = clock.millis() + UNDO_WINDOW_MILLIS
        _deadline.value = deadline
        job = scope.launch {
            delay(UNDO_WINDOW_MILLIS)
            val commit = lock.withLock {
                (_deadline.value == deadline).also { current ->
                    if (current) {
                        _deadline.value = null
                        job = null
                    }
                }
            }
            if (commit) clear()
        }
    }

    /** Cancels a pending clear; returns whether there was one to cancel. */
    suspend fun undo(): Boolean = lock.withLock {
        val pending = _deadline.value != null
        job?.cancel()
        job = null
        _deadline.value = null
        pending
    }

    companion object {
        const val UNDO_WINDOW_MILLIS = 5_000L
    }
}

/**
 * `Clear Usenet settings`: every NNTP provider, every Newznab indexer and every `usenet/` setting (the toggle reads
 * OFF again), then the engine's pooled and warm connections are closed. Never touches a Debrid row or `debrid/` key,
 * addon rows, LAST USED streams or the caches `Clear cache` owns.
 */
@Singleton
class UsenetSettingsClear @Inject constructor(
    repository: UsenetSourcesRepository,
    store: UsenetSettingsStore,
    /** Lazy: building the engine opens its disk cache, so it is resolved on IO. */
    engine: Lazy<NntpEngine>,
    @ApplicationScope scope: CoroutineScope,
    clock: Clock,
    @IoDispatcher io: CoroutineDispatcher,
) : PendingSourceClear(
    scope,
    clock,
    clear = {
        repository.clearAll()
        store.clearAll()
        withContext(io) { engine.get().configure(emptyList(), UsenetPerformance().engineSettings()) }
    },
)

/**
 * `Clear Debrid settings`: pending code sign-ins stop (any that still completes is discarded), then every Debrid
 * account with its keys/tokens and every `debrid/` setting go (the toggle reads OFF again). Never touches a Usenet
 * table or `usenet/` key, addon rows or LAST USED streams.
 */
@Singleton
class DebridSettingsClear @Inject constructor(
    repository: DebridSourcesRepository,
    store: DebridSettingsStore,
    sessions: DeviceAuthSessions,
    @ApplicationScope scope: CoroutineScope,
    clock: Clock,
) : PendingSourceClear(
    scope,
    clock,
    clear = {
        sessions.cancelAll()
        repository.deleteAll()
        store.clearAll()
    },
)
