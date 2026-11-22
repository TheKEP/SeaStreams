package com.seastreams.tv.core.cache

import android.content.Context
import coil3.SingletonImageLoader
import com.seastreams.tv.core.image.IMAGE_CACHE_DIR
import com.seastreams.tv.core.network.NetworkModule
import com.seastreams.tv.core.util.IoDispatcher
import com.seastreams.tv.usenet.cache.NzbFileCache
import com.seastreams.tv.usenet.cache.SegmentDiskCache
import dagger.Binds
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.Cache
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Resolves `context.cacheDir`, which may create the folder, so it is only called on the IO dispatcher. */
fun interface CacheDirProvider {
    fun get(): File
}

/** The image loader's caches, behind an interface so JVM tests don't need an Android context. */
interface ImageCaches {
    fun clearMemory()

    fun clearDisk()
}

/**
 * A bounded cache that owns one `cacheDir` folder ([dirName], listed in [CacheManager.MANAGED_DIRS]) and empties
 * itself through its own API, so its in-memory index stays consistent (ARCHITECTURE §3). Called on the IO dispatcher.
 */
interface ManagedCache {
    val dirName: String

    fun clear()

    fun sizeBytes(): Long
}

/** Coil's singleton loader (configured by `buildImageLoader`). */
class CoilImageCaches @Inject constructor(@ApplicationContext private val context: Context) : ImageCaches {
    override fun clearMemory() {
        SingletonImageLoader.get(context).memoryCache?.clear()
    }

    override fun clearDisk() {
        SingletonImageLoader.get(context).diskCache?.clear()
    }
}

/**
 * Owns everything SeaStreams keeps in `context.cacheDir`: the 100 MB OkHttp cache ([NetworkModule.HTTP_CACHE_DIR]),
 * the 150 MB Coil cache ([IMAGE_CACHE_DIR]), the bounded Usenet caches ([managedCaches]) and any stray files left by
 * older builds or libraries. Other playback and trailers stream through memory only. Every call runs on [io].
 */
@Singleton
class CacheManager @Inject constructor(
    private val cacheDirProvider: CacheDirProvider,
    /** Lazy: building the OkHttp cache opens its journal, so it is resolved on [io], never the main thread. */
    private val httpCache: Lazy<Cache>,
    private val imageCaches: ImageCaches,
    private val managedCaches: Set<@JvmSuppressWildcards ManagedCache>,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /** Bytes currently used by every file under `cacheDir`. */
    suspend fun totalSizeBytes(): Long = withContext(io) { sizeOf(cacheDirProvider.get()) }

    /**
     * Empties the image caches (memory + disk), the HTTP cache and every [ManagedCache] through their own APIs, so
     * their live LRU journals and indexes stay valid, then deletes every other entry in `cacheDir`. Returns the size left afterwards.
     */
    suspend fun clearAll(): Long = withContext(io) {
        imageCaches.clearMemory()
        imageCaches.clearDisk()
        httpCache.get().evictAll()
        managedCaches.forEach { it.clear() }
        val dir = cacheDirProvider.get()
        unmanagedEntries(dir).forEach { it.deleteRecursively() }
        sizeOf(dir)
    }

    /**
     * Startup cleanup: deletes stray `cacheDir` entries older than [ORPHAN_MIN_AGE_MILLIS]. Recent ones are kept
     * in case a library is mid-write at launch; the next start (or Clear Cache) removes them.
     */
    suspend fun sweepOrphans(nowMillis: Long = System.currentTimeMillis()): Unit = withContext(io) {
        unmanagedEntries(cacheDirProvider.get())
            .filter { nowMillis - it.lastModified() > ORPHAN_MIN_AGE_MILLIS }
            .forEach { it.deleteRecursively() }
    }

    private fun unmanagedEntries(dir: File): List<File> =
        dir.listFiles().orEmpty().filter { it.name !in MANAGED_DIRS }

    private fun sizeOf(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    companion object {
        val MANAGED_DIRS: Set<String> =
            setOf(NetworkModule.HTTP_CACHE_DIR, IMAGE_CACHE_DIR, SegmentDiskCache.DIR, NzbFileCache.DIR)
        const val ORPHAN_MIN_AGE_MILLIS: Long = 60L * 60 * 1000
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class CacheModule {
    @Binds
    abstract fun bindImageCaches(impl: CoilImageCaches): ImageCaches

    companion object {
        @Provides
        fun provideCacheDir(@ApplicationContext context: Context): CacheDirProvider = CacheDirProvider { context.cacheDir }
    }
}
