package com.seastreams.tv.core.webserver

import dagger.Lazy
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

sealed interface PortalStatus {
    data object Stopped : PortalStatus
    data class Running(val port: Int) : PortalStatus
    data class Failed(val reason: String) : PortalStatus
}

/**
 * Embedded Ktor CIO server exposing the Web Portal on the LAN (all interfaces, port [DEFAULT_PORT]).
 *
 * [start]/[stop] are idempotent and blocking (binding a socket), so call them off the main thread.
 * The [PortalController] graph (repositories, HTTP client, disk cache) is resolved lazily on that
 * same off-main path, so injecting this server into an Activity stays cheap.
 */
@Singleton
class LocalPortalServer @Inject constructor(private val controller: Lazy<PortalController>) {

    /** Test/composition convenience for an already-built controller. */
    constructor(controller: PortalController) : this(Lazy { controller })

    private val lock = Any()
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val _status = MutableStateFlow<PortalStatus>(PortalStatus.Stopped)
    val status: StateFlow<PortalStatus> = _status.asStateFlow()

    /** Serial queue: start/stop requests from successive activity instances run strictly in order. */
    private val requests = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    /** Non-blocking [start] for UI callers (e.g. `Activity.onStart`). */
    fun requestStart() {
        requests.launch { start() }
    }

    /** Non-blocking [stop] for UI callers (e.g. `Activity.onStop`). */
    fun requestStop() {
        requests.launch { stop() }
    }

    /** Starts the server if needed and returns the bound port, or `null` if binding failed. */
    fun start(port: Int = DEFAULT_PORT, host: String = ALL_INTERFACES): Int? = synchronized(lock) {
        (_status.value as? PortalStatus.Running)?.let { return it.port }
        val candidate = embeddedServer(CIO, port = port, host = host) { portalModule(controller.get()) }
        return try {
            candidate.start(wait = false)
            val bound = runBlocking { candidate.engine.resolvedConnectors().first().port }
            server = candidate
            _status.value = PortalStatus.Running(bound)
            bound
        } catch (e: Exception) {
            // Typically a BindException (port taken by a stale process); surfaced on the TV screen.
            runCatching { candidate.stop(gracePeriodMillis = 0, timeoutMillis = 0) }
            _status.value = PortalStatus.Failed(e.message ?: "Port $port is unavailable")
            null
        }
    }

    fun stop() = synchronized(lock) {
        server?.stop(gracePeriodMillis = STOP_GRACE_MILLIS, timeoutMillis = STOP_TIMEOUT_MILLIS)
        server = null
        _status.value = PortalStatus.Stopped
    }

    companion object {
        const val DEFAULT_PORT = 8765
        const val ALL_INTERFACES = "0.0.0.0"
        private const val STOP_GRACE_MILLIS = 200L
        private const val STOP_TIMEOUT_MILLIS = 1_000L
    }
}
