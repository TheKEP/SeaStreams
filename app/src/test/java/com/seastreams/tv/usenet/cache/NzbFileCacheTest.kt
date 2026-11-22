package com.seastreams.tv.usenet.cache

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class NzbFileCacheTest {
    @TempDir
    lateinit var cacheDir: File

    private var now = 1_700_000_000_000L

    private fun cache(maxFiles: Int = 3) = NzbFileCache({ cacheDir }, { now }, maxFiles, NzbFileCache.MAX_AGE_MILLIS)

    private fun files() = File(cacheDir, NzbFileCache.DIR).listFiles().orEmpty().toList()

    @Test
    fun `stores gzip under a hashed name that never contains the key`() {
        val cache = cache()
        val nzb = "<nzb>" + "<segment>x</segment>".repeat(1_000) + "</nzb>"
        cache.put("https://indexer/api?t=get&apikey=SECRET", nzb)
        assertEquals(nzb, cache.get("https://indexer/api?t=get&apikey=SECRET"))
        val stored = files().single()
        assertFalse("SECRET" in stored.name)
        assertTrue(stored.length() < nzb.length / 10, "compressed")
    }

    @Test
    fun `keeps at most the newest files and expires after 14 days`() {
        val cache = cache(maxFiles = 3)
        repeat(5) {
            now += 1_000
            cache.put("k$it", "nzb $it")
        }
        assertEquals(3, files().size)
        assertNull(cache.get("k0"))
        assertEquals("nzb 4", cache.get("k4"))
        now += NzbFileCache.MAX_AGE_MILLIS + 1
        assertNull(cache.get("k4"))
        cache.prune()
        assertTrue(files().isEmpty())
    }

    @Test
    fun `clear removes everything`() {
        val cache = cache()
        cache.put("a", "x")
        cache.clear()
        assertNull(cache.get("a"))
        assertEquals(0L, cache.sizeBytes())
    }
}
