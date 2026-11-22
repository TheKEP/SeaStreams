package com.seastreams.tv.usenet.nntp

import com.seastreams.tv.usenet.cache.CachedSegmentSource
import com.seastreams.tv.usenet.cache.SegmentDiskCache
import com.seastreams.tv.usenet.yenc.SegmentBufferPool
import com.seastreams.tv.usenet.yenc.YencDecoder
import java.io.Closeable
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app-wide NNTP engine (ARCHITECTURE §10). Holds one [ProviderWorkerPool] per enabled provider, keyed by
 * [NntpEndpoint.fingerprint]: [configure] keeps the pools (and their open connections) of providers whose
 * login, server and limits are unchanged, and closes the rest. Every fetch takes a permit of the global
 * [PrioritySemaphore] (`maxConcurrentDownloads`, `0` = Σ connections × depth), then fails over through the
 * [ProviderSelector] order. [cachedSource] puts the bounded [SegmentDiskCache] in front of it, whose cap
 * follows the configured profile. Pure JVM: no Android types, so it is tested against a real local socket.
 */
@Singleton
class NntpEngine internal constructor(
    private val opener: SocketOpener,
    private val clock: () -> Long,
    private val diskCache: SegmentDiskCache?,
) : SegmentSource, Closeable {

    @Inject
    constructor(diskCache: SegmentDiskCache) : this(DefaultSocketOpener, monotonicMillis, diskCache)

    val bufferPool = SegmentBufferPool()
    private val decoder = YencDecoder(bufferPool)
    val warm = WarmConnections(opener, clock)
    val selector = ProviderSelector(clock)
    private val pools = LinkedHashMap<String, ProviderWorkerPool>()
    private var semaphore = PrioritySemaphore(1)

    @Volatile
    var settings = NntpEngineSettings()
        private set

    /** Applies the enabled providers (in their sort order) and the performance values. */
    fun configure(endpoints: List<NntpEndpoint>, next: NntpEngineSettings) {
        val retired = ArrayList<ProviderWorkerPool>()
        synchronized(this) {
            if (next != settings) {
                retired += pools.values
                pools.clear()
                settings = next
            }
            val wanted = endpoints.associateBy { it.fingerprint }
            pools.keys.filter { it !in wanted }.forEach { key -> pools.remove(key)?.let(retired::add) }
            val ordered = LinkedHashMap<String, ProviderWorkerPool>()
            for ((key, endpoint) in wanted) {
                ordered[key] = pools[key] ?: ProviderWorkerPool(endpoint, settings, decoder, opener, warm, clock)
            }
            pools.clear()
            pools.putAll(ordered)
            val auto = endpoints.sumOf { it.maxConnections * it.pipelineDepth }
            val permits = (if (settings.maxConcurrentDownloads > 0) settings.maxConcurrentDownloads else auto).coerceAtLeast(1)
            if (permits != semaphore.capacity) semaphore = PrioritySemaphore(permits, settings.streamingShare)
        }
        diskCache?.profileCapBytes = next.diskCacheBytes
        warm.retain(endpoints.map { it.fingerprint }.toSet())
        retired.forEach { it.close() }
    }

    /** What a stream reads from: the disk cache, then the providers (written through). */
    val cachedSource: SegmentSource by lazy { diskCache?.let { CachedSegmentSource(this, it, bufferPool) } ?: this }

    fun pools(): List<ProviderWorkerPool> = synchronized(this) { pools.values.toList() }

    fun downloadPermits(): PrioritySemaphore = synchronized(this) { semaphore }

    /** Opens `warmConnections` sign-ins to each preferred primary (called when a stream search starts). */
    fun warmUp() {
        val current = pools()
        val count = settings.warmConnections
        if (count <= 0) return
        val primaries = current.filter { !it.endpoint.isBackup && it.healthy }
        val best = primaries.minOfOrNull { it.endpoint.priority } ?: return
        primaries.filter { it.endpoint.priority == best }.forEach { warm.warm(it.endpoint, count, settings) }
    }

    override fun fetch(messageId: String, part: Int, priority: FetchPriority): SegmentFetch =
        gated(messageId, priority) { pool -> pool.body(messageId, part, priority) }

    /** STAT through the same failover (census, Milestone 9): `true` = some provider has it. */
    fun stat(messageId: String, priority: FetchPriority = FetchPriority.LOW): NntpFuture<Boolean> =
        gated(messageId, priority) { pool -> pool.stat(messageId, priority) }

    private fun <T> gated(messageId: String, priority: FetchPriority, submit: (ProviderWorkerPool) -> NntpFuture<T>): NntpFuture<T> {
        val outer = NntpFuture<T>()
        val (candidates, permits) = synchronized(this) { selector.order(pools.values.toList()) to semaphore }
        if (candidates.isEmpty()) {
            outer.fail(NntpException(NntpErrorKind.NO_PROVIDERS, "No Usenet provider is enabled"))
            return outer
        }
        val ticket = permits.acquire(priority) { permit ->
            outer.onDone { permit.release() }
            selector.failover(outer, messageId, candidates, submit)
        }
        outer.onDone { if (it.cancelled) ticket.cancel() }
        return outer
    }

    override fun close() {
        val all = synchronized(this) { pools.values.toList().also { pools.clear() } }
        all.forEach { it.close() }
        warm.close()
    }
}
