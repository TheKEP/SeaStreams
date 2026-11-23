package com.seastreams.tv.player.external

import android.content.Context
import com.seastreams.tv.core.util.IoDispatcher
import com.seastreams.tv.core.webserver.LocalPortalServer
import com.seastreams.tv.core.webserver.PortalLease
import com.seastreams.tv.domain.model.StreamSource
import com.seastreams.tv.usenet.session.UsenetSessionRegistry
import com.seastreams.tv.usenet.session.UsenetStreamRegistry
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands Usenet streams to external players (PRD §12), called from `PlaybackViewModel.start`: the opaque
 * `seastreams-nntp://<token>` URL becomes `http://127.0.0.1:<port>/nntp/<token>/<name>` on the loopback proxy.
 * Before the hand-off the release is opened and its head read (warm-up), so a release that can't stream fails here
 * and failover can move on, and the player's first request is answered from memory. A [PortalLease] plus the
 * [NntpProxyService] keep the proxy alive until [finish] (the player returned). Other streams pass through as-is.
 */
@Singleton
class ExternalStreamUrls @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val portal: LocalPortalServer,
    /** Lazy: the session graph (engine, caches) is resolved on IO. */
    private val sessions: Lazy<UsenetSessionRegistry>,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private var handoff: PortalLease? = null

    /** [stream] ready for an external player; throws an [IOException] when its Usenet release can't be opened. */
    suspend fun prepare(stream: StreamSource, title: String): StreamSource {
        val token = UsenetStreamRegistry.tokenOf(stream.url) ?: return stream
        val lease = portal.acquireLease()
        try {
            val port = runInterruptible(io) { portal.start() } ?: throw IOException("SeaStreams couldn't start its local stream server")
            val name = runInterruptible(io) { warmUp(token) }
            synchronized(this) {
                handoff?.close()
                handoff = lease
            }
            NntpProxyService.start(context, title)
            return stream.copy(url = proxyUrl(port, token, name), headers = emptyMap())
        } catch (e: Throwable) {
            lease.close()
            throw e
        }
    }

    /** The external player returned (or never started): the proxy may stop with the app. */
    fun finish() {
        val lease = synchronized(this) { handoff.also { handoff = null } } ?: return
        NntpProxyService.stop(context)
        lease.close()
    }

    private fun warmUp(token: String): String {
        val registry = sessions.get()
        val session = registry.acquire(token)
        try {
            val head = ByteArray(minOf(WARM_BYTES.toLong(), session.size).toInt())
            var filled = 0
            while (filled < head.size) {
                val n = session.read(filled.toLong(), head, filled, head.size - filled)
                if (n <= 0) break
                filled += n
            }
            return session.urlName
        } finally {
            registry.release(token)
        }
    }

    companion object {
        private const val WARM_BYTES = 256 * 1024

        fun proxyUrl(port: Int, token: String, name: String): String =
            "http://127.0.0.1:$port/nntp/$token/" + URLEncoder.encode(name, "UTF-8").replace("+", "%20")
    }
}
