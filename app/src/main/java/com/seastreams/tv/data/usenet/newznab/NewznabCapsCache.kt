package com.seastreams.tv.data.usenet.newznab

import com.seastreams.tv.core.cache.CacheDirProvider
import com.seastreams.tv.core.cache.ManagedCache
import com.seastreams.tv.core.util.sha1Hex
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Each indexer's `t=caps` answer, kept [maxAgeMillis] (14 days) in `cacheDir/newznab_caps` (ARCHITECTURE §3), so a
 * search costs one request instead of two. Keyed by SHA-1 of the API URL: no key or address in a filename.
 * Blocking: call on the IO dispatcher.
 */
@Singleton
class NewznabCapsCache internal constructor(
    private val cacheDir: CacheDirProvider,
    private val now: () -> Long,
    private val maxAgeMillis: Long,
) : ManagedCache {

    @Inject
    constructor(cacheDir: CacheDirProvider) : this(cacheDir, System::currentTimeMillis, MAX_AGE_MILLIS)

    override val dirName: String = DIR
    private val memory = HashMap<String, Pair<Long, NewznabCaps>>()

    @Synchronized
    fun get(apiUrl: String): NewznabCaps? {
        val key = sha1Hex(apiUrl)
        memory[key]?.let { (at, caps) -> if (now() - at <= maxAgeMillis) return caps else memory.remove(key) }
        val file = File(dir(), "$key.xml")
        if (!file.isFile) return null
        if (now() - file.lastModified() > maxAgeMillis) {
            file.delete()
            return null
        }
        val caps = try {
            NewznabXmlScanner.caps(file.readText())
        } catch (e: IOException) {
            return null
        }
        memory[key] = file.lastModified() to caps
        return caps
    }

    @Synchronized
    fun put(apiUrl: String, xml: String) {
        val key = sha1Hex(apiUrl)
        memory[key] = now() to NewznabXmlScanner.caps(xml)
        try {
            val dir = dir().apply { mkdirs() }
            val temp = File(dir, "$key.tmp")
            temp.writeText(xml.take(MAX_CAPS_CHARS))
            val target = File(dir, "$key.xml")
            if (!temp.renameTo(target)) {
                target.delete()
                temp.renameTo(target)
            }
            target.setLastModified(now())
        } catch (e: IOException) {
            // Memory still holds it; the next start simply asks the indexer again.
        }
    }

    @Synchronized
    override fun clear() {
        memory.clear()
        dir().listFiles().orEmpty().forEach { it.delete() }
    }

    override fun sizeBytes(): Long = dir().listFiles().orEmpty().sumOf { it.length() }

    private fun dir() = File(cacheDir.get(), DIR)

    companion object {
        const val DIR = "newznab_caps"
        const val MAX_AGE_MILLIS = 14L * 24 * 60 * 60 * 1000
        private const val MAX_CAPS_CHARS = 256 * 1024
    }
}
