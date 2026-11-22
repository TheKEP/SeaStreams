package com.seastreams.tv.core.cache

import com.seastreams.tv.core.image.IMAGE_CACHE_DIR
import com.seastreams.tv.core.image.IMAGE_DISK_CACHE_BYTES
import com.seastreams.tv.core.network.NetworkModule
import com.seastreams.tv.testutil.TestHttpServer
import com.seastreams.tv.usenet.cache.NzbFileCache
import com.seastreams.tv.usenet.cache.SegmentDiskCache
import com.seastreams.tv.usenet.yenc.DecodedSegment
import com.seastreams.tv.usenet.yenc.SegmentBufferPool
import io.ktor.http.ContentType
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class CacheManagerTest {

    @TempDir
    lateinit var cacheDir: File

    /** Names of the threads that touched the cache dir, the OkHttp cache or the image caches. */
    private val ioThreads = CopyOnWriteArrayList<String>()
    private val executor = Executors.newSingleThreadExecutor { Thread(it, IO_THREAD) }
    private val io = executor.asCoroutineDispatcher()
    private val imageCaches = FakeImageCaches()

    private lateinit var server: TestHttpServer
    private lateinit var httpCache: Cache
    private lateinit var manager: CacheManager
    private lateinit var segments: SegmentDiskCache
    private lateinit var nzbs: NzbFileCache

    @BeforeEach
    fun setUp() {
        server = TestHttpServer {
            get("/poster") {
                call.response.header("Cache-Control", "public, max-age=3600")
                call.respondText("x".repeat(PAYLOAD_CHARS), ContentType.Text.Plain)
            }
        }
        httpCache = Cache(File(cacheDir, NetworkModule.HTTP_CACHE_DIR), NetworkModule.HTTP_CACHE_SIZE_BYTES)
        segments = SegmentDiskCache({ record(); cacheDir }, { 100L * 1024 * 1024 * 1024 }, { it.run() }, 16)
        nzbs = NzbFileCache({ record(); cacheDir }, System::currentTimeMillis, NzbFileCache.MAX_FILES, NzbFileCache.MAX_AGE_MILLIS)
        manager = CacheManager(
            cacheDirProvider = { record(); cacheDir },
            httpCache = { record(); httpCache },
            imageCaches = imageCaches,
            managedCaches = setOf(segments, nzbs),
            io = io,
        )
    }

    @AfterEach
    fun tearDown() {
        httpCache.close()
        server.close()
        io.close()
    }

    @Test
    fun `an empty cache dir measures zero`() = runBlocking {
        assertEquals(0L, manager.totalSizeBytes())
    }

    @Test
    fun `size sums every file in the HTTP, image and stray folders`() = runBlocking {
        write(File(cacheDir, "$IMAGE_CACHE_DIR/a/b.bin"), 3_000)
        write(File(cacheDir, "stray.tmp"), 500)
        write(File(cacheDir, "old_dir/nested/file"), 200)
        val httpBytes = File(cacheDir, NetworkModule.HTTP_CACHE_DIR).walkTopDown().filter { it.isFile }.sumOf { it.length() }

        assertEquals(httpBytes + 3_700, manager.totalSizeBytes())
    }

    @Test
    fun `clearing empties the HTTP cache and both image caches and deletes stray files`() = runBlocking {
        fetchPoster()
        assertTrue(httpCache.size() > 0)
        write(File(cacheDir, "stray.tmp"), 4_000)
        write(File(cacheDir, "old_dir/nested/file"), 4_000)

        val left = manager.clearAll()

        assertEquals(0L, httpCache.size())
        assertEquals(1, imageCaches.memoryClears)
        assertEquals(1, imageCaches.diskClears)
        assertFalse(File(cacheDir, "stray.tmp").exists())
        assertFalse(File(cacheDir, "old_dir").exists())
        assertTrue(File(cacheDir, NetworkModule.HTTP_CACHE_DIR).exists(), "the live OkHttp journal stays in place")
        assertTrue(left < SMALL_JOURNAL_BYTES, "only journals remain, got $left bytes")
        assertEquals(left, manager.totalSizeBytes())
    }

    @Test
    fun `the HTTP cache keeps working after a clear`() = runBlocking {
        fetchPoster()
        manager.clearAll()

        fetchPoster()
        assertTrue(httpCache.size() > 0)
    }

    @Test
    fun `the startup sweep removes only old stray entries`() = runBlocking {
        val now = System.currentTimeMillis()
        val old = File(cacheDir, "orphan.tmp").also { write(it, 100) }.apply { setLastModified(now - 2 * HOUR) }
        val fresh = File(cacheDir, "in-flight.tmp").also { write(it, 100) }.apply { setLastModified(now) }
        val image = File(cacheDir, IMAGE_CACHE_DIR).apply { mkdirs(); setLastModified(now - 2 * HOUR) }

        manager.sweepOrphans(now)

        assertFalse(old.exists())
        assertTrue(fresh.exists())
        assertTrue(image.exists())
    }

    @Test
    fun `no cache disk work runs on the caller's thread`() = runBlocking {
        val caller = threadName()
        manager.totalSizeBytes()
        manager.clearAll()
        manager.sweepOrphans()

        assertTrue(ioThreads.isNotEmpty())
        assertTrue(ioThreads.all { it == IO_THREAD }, "touched on $ioThreads")
        assertTrue(caller != IO_THREAD)
        assertTrue(imageCaches.threads.all { it == IO_THREAD }, "image caches cleared on ${imageCaches.threads}")
    }

    @Test
    fun `cache caps are fixed sizes, never a share of free storage`() {
        assertEquals(100L * 1024 * 1024, NetworkModule.HTTP_CACHE_SIZE_BYTES)
        assertEquals("http_cache", NetworkModule.HTTP_CACHE_DIR)
        assertEquals(150L * 1024 * 1024, IMAGE_DISK_CACHE_BYTES)
        assertEquals("image_cache", IMAGE_CACHE_DIR)
        assertEquals(setOf("http_cache", "image_cache", "nntp_segments", "nntp_nzb"), CacheManager.MANAGED_DIRS)
    }

    @Test
    fun `clear cache empties the Usenet caches through their own APIs and keeps their folders managed`() = runBlocking {
        val pool = SegmentBufferPool()
        segments.putAsync("a@x", DecodedSegment(ByteArray(5_000), 5_000, 0, 5_000, "a.bin", 1, 1))
        nzbs.put("https://indexer/getnzb?id=1", "<nzb/>")
        assertTrue(segments.indexedBytes() > 0)
        assertTrue(manager.totalSizeBytes() >= 5_000)

        manager.clearAll()

        assertEquals(0L, segments.indexedBytes())
        assertEquals(null, segments.get("a@x", pool))
        assertEquals(null, nzbs.get("https://indexer/getnzb?id=1"))
        assertEquals(0L, segments.sizeBytes() + nzbs.sizeBytes())
        segments.putAsync("b@x", DecodedSegment(ByteArray(10), 10, 0, 10, "b.bin", 1, 1))
        assertTrue(segments.get("b@x", pool) != null, "the cache keeps working after a clear")
    }

    @Test
    fun `the startup sweep never deletes the Usenet caches`() = runBlocking {
        val now = System.currentTimeMillis()
        segments.putAsync("a@x", DecodedSegment(ByteArray(100), 100, 0, 100, "a.bin", 1, 1))
        nzbs.put("k", "<nzb/>")
        File(cacheDir, SegmentDiskCache.DIR).setLastModified(now - 2 * HOUR)
        File(cacheDir, NzbFileCache.DIR).setLastModified(now - 2 * HOUR)

        manager.sweepOrphans(now)

        assertTrue(segments.get("a@x", SegmentBufferPool()) != null)
        assertEquals("<nzb/>", nzbs.get("k"))
    }

    private fun record() {
        ioThreads += threadName()
    }

    private fun fetchPoster() {
        OkHttpClient.Builder().cache(httpCache).build()
            .newCall(Request.Builder().url(server.url("/poster")).build())
            .execute()
            .use { it.body.string() }
    }

    private fun write(file: File, bytes: Int) {
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(bytes))
    }

    private class FakeImageCaches : ImageCaches {
        var memoryClears = 0
        var diskClears = 0
        val threads = CopyOnWriteArrayList<String>()

        override fun clearMemory() {
            memoryClears++
            threads += threadName()
        }

        override fun clearDisk() {
            diskClears++
            threads += threadName()
        }
    }

    private companion object {
        const val IO_THREAD = "cache-io"
        const val PAYLOAD_CHARS = 20_000
        const val SMALL_JOURNAL_BYTES = 1_024L
        const val HOUR = 60L * 60 * 1000
    }
}

/** Without coroutine debug mode's ` @coroutine#N` suffix. */
private fun threadName(): String = Thread.currentThread().name.substringBefore(" @coroutine")
