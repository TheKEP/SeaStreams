package com.seastreams.tv.usenet.cache

import com.seastreams.tv.core.cache.ManagedCache
import com.seastreams.tv.usenet.nntp.FetchPriority
import com.seastreams.tv.usenet.nntp.SegmentFetch
import com.seastreams.tv.usenet.nntp.SegmentSource
import com.seastreams.tv.usenet.yenc.SegmentBufferPool
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Puts the [SegmentDiskCache] in front of [network] (normally the `NntpEngine`): a lookup runs on [readers]
 * (never the caller's thread), a miss goes to the network, and what the network decodes is written through.
 */
class CachedSegmentSource(
    private val network: SegmentSource,
    private val disk: SegmentDiskCache,
    private val pool: SegmentBufferPool,
    private val readers: Executor = defaultReaders(),
) : SegmentSource {

    override fun fetch(messageId: String, part: Int, priority: FetchPriority): SegmentFetch {
        val outer = SegmentFetch()
        readers.execute {
            if (outer.isDone) return@execute
            val cached = disk.get(messageId, pool, part)
            if (cached != null) {
                if (!outer.complete(cached)) cached.release()
                return@execute
            }
            val inner = network.fetch(messageId, part, priority)
            outer.onDone { if (it.cancelled) inner.cancel() }
            inner.onDone { done ->
                val segment = done.result
                if (segment != null) {
                    disk.putAsync(messageId, segment)
                    outer.complete(segment)
                } else {
                    outer.fail(done.failure ?: java.io.IOException("The article couldn't be fetched"))
                }
            }
        }
        return outer
    }

    private companion object {
        fun defaultReaders(): Executor = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, LinkedBlockingQueue()) {
            Thread(it, "nntp-cache-reader").apply { isDaemon = true }
        }.apply { allowCoreThreadTimeOut(true) }
    }
}

/** Registers the Usenet caches with `CacheManager` (cleared by *Clear cache*, spared by the orphan sweep). */
@Module
@InstallIn(SingletonComponent::class)
abstract class UsenetCacheModule {
    @Binds
    @IntoSet
    abstract fun segmentCache(cache: SegmentDiskCache): ManagedCache

    @Binds
    @IntoSet
    abstract fun nzbCache(cache: NzbFileCache): ManagedCache
}
