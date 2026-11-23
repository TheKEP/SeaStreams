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

/** Keeps the portal server (and its loopback `/nntp` proxy) running while held; [close] gives it back. */
class PortalLease internal constructor(private val server: LocalPortalServer) : java.io.Closeable {
    private val released = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun close() {
        if (released.compareAndSet(false, true)) server.releaseLease()
    }
}

/**
 * Embedded Ktor CIO server exposing the Web Portal on the LAN (all interfaces, port [DEFAULT_PORT]), plus the
 * loopback-only `/nntp` proxy external players read Usenet streams from ([NntpProxy]).
 *
 * [start]/[stop] are idempotent and blocking (binding a socket), so call them off the main thread.
 * The [PortalController] / [PortalUsenetController] / [NntpProxy] graphs (repositories, HTTP client, disk cache) are resolved
 * lazily on that same off-main path, so injecting this server into an Activity stays cheap.
 *
 * **Lease (PRD §12):** the UI asks for the server while the app is in the foreground ([requestStart] / [requestStop]);
 * an external player streaming Usenet holds a [PortalLease]. The server stops only when neither wants it, so
 * `MainActivity.onStop` (the external player taking the screen) never cuts off the stream it is playing.
 */
@Singleton
class LocalPortalServer @Inject constructor(
    private val controller: Lazy<PortalController>,
    private val usenet: Lazy<PortalUsenetController>,
    private val proxy: Lazy<NntpProxy>,
) {

    /** Test/composition convenience for already-built controllers. */
    constructor(controller: PortalController, usenet: PortalUsenetController, proxy: NntpProxy? = null) :
        this(Lazy { controller }, Lazy { usenet }, Lazy { proxy ?: error("No Usenet proxy") }) {
        proxyAvailable = proxy != null
    }

    private var proxyAvailable = true
    private val leaseLock = Any()
    private var leases = 0
    private var uiWanted = false

    /** Whether an external player's stream currently holds the server. */
    val leased: Boolean get() = synchronized(leaseLock) { leases > 0 }

    /** Holds the server running (starting it if needed) until the lease is closed. */
    fun acquireLease(): PortalLease {
        synchronized(leaseLock) { leases++ }
        requests.launch { start() }
        return PortalLease(this)
    }

    internal fun releaseLease() {
        synchronized(leaseLock) { leases = (leases - 1).coerceAtLeast(0) }
        requests.launch { stopIfUnwanted() }
    }

    private fun stopIfUnwanted() {
        if (synchronized(leaseLock) { leases == 0 && !uiWanted }) stop()
    }

    private val lock = Any()
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val _status = MutableStateFlow<PortalStatus>(PortalStatus.Stopped)
    val status: StateFlow<PortalStatus> = _status.asStateFlow()

    /** Serial queue: start/stop requests from successive activity instances run strictly in order. */
    private val requests = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    /** Non-blocking [start] for UI callers (e.g. `Activity.onStart`). */
    fun requestStart() {
        synchronized(leaseLock) { uiWanted = true }
        requests.launch { start() }
    }

    /** Non-blocking stop for UI callers (e.g. `Activity.onStop`); deferred while a [PortalLease] is held. */
    fun requestStop() {
        synchronized(leaseLock) { uiWanted = false }
        requests.launch { stopIfUnwanted() }
    }

    /** The UI no longer needs the server, but a lease does: it stops when the last [PortalLease] closes. */
    fun releaseUiClaim() {
        synchronized(leaseLock) { uiWanted = false }
    }

    /** Starts the server if needed and returns the bound port, or `null` if binding failed. */
    fun start(port: Int = DEFAULT_PORT, host: String = ALL_INTERFACES): Int? = synchronized(lock) {
        (_status.value as? PortalStatus.Running)?.let { return it.port }
        val candidate = embeddedServer(CIO, port = port, host = host) { portalModule(controller.get(), usenet.get(), if (proxyAvailable) proxy.get() else null) }
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
