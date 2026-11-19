package com.seastreams.tv.core.cache

import com.seastreams.tv.core.image.IMAGE_CACHE_DIR
import com.seastreams.tv.core.image.IMAGE_DISK_CACHE_BYTES
import com.seastreams.tv.core.network.NetworkModule
import com.seastreams.tv.testutil.TestHttpServer
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

    @BeforeEach
    fun setUp() {
        server = TestHttpServer {
            get("/poster") {
                call.response.header("Cache-Control", "public, max-age=3600")
                call.respondText("x".repeat(PAYLOAD_CHARS), ContentType.Text.Plain)
            }
        }
        httpCache = Cache(File(cacheDir, NetworkModule.HTTP_CACHE_DIR), NetworkModule.HTTP_CACHE_SIZE_BYTES)
        manager = CacheManager(
            cacheDirProvider = { record(); cacheDir },
            httpCache = { record(); httpCache },
            imageCaches = imageCaches,
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
        assertEquals(setOf("http_cache", "image_cache"), CacheManager.MANAGED_DIRS)
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
