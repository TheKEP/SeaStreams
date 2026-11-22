package com.seastreams.tv.usenet.cache

import com.seastreams.tv.core.cache.CacheDirProvider
import com.seastreams.tv.core.cache.ManagedCache
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Grabbed NZBs, gzip-compressed in `cacheDir/nntp_nzb` (ARCHITECTURE §3): at most [maxFiles] files, each kept
 * [maxAgeMillis] (14 days), so re-opening a release doesn't hit the indexer's grab limit again. Keys are hashed,
 * so no indexer URL or API key appears in a filename. Blocking: call on the IO dispatcher.
 */
@Singleton
class NzbFileCache internal constructor(
    private val cacheDir: CacheDirProvider,
    private val now: () -> Long,
    private val maxFiles: Int,
    private val maxAgeMillis: Long,
) : ManagedCache {

    @Inject
    constructor(cacheDir: CacheDirProvider) : this(cacheDir, System::currentTimeMillis, MAX_FILES, MAX_AGE_MILLIS)

    override val dirName: String = DIR

    @Synchronized
    fun get(key: String): String? {
        val file = fileOf(key)
        if (!file.isFile) return null
        if (now() - file.lastModified() > maxAgeMillis) {
            file.delete()
            return null
        }
        return try {
            GZIPInputStream(Files.newInputStream(file.toPath())).use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: IOException) {
            file.delete()
            null
        }
    }

    @Synchronized
    fun put(key: String, nzb: String) {
        val dir = dir().apply { mkdirs() }
        val target = fileOf(key)
        val temp = File(dir, target.name + ".tmp")
        try {
            GZIPOutputStream(temp.outputStream().buffered()).use { it.write(nzb.toByteArray(Charsets.UTF_8)) }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            target.setLastModified(now())
        } catch (e: IOException) {
            temp.delete()
            return
        }
        prune()
    }

    /** Deletes expired files, then the oldest beyond [maxFiles]. */
    @Synchronized
    fun prune() {
        val files = dir().listFiles().orEmpty().filter { it.isFile }
        val (expired, kept) = files.partition { it.name.endsWith(".tmp") || now() - it.lastModified() > maxAgeMillis }
        expired.forEach { it.delete() }
        kept.sortedByDescending { it.lastModified() }.drop(maxFiles).forEach { it.delete() }
    }

    @Synchronized
    override fun clear() {
        dir().listFiles().orEmpty().forEach { runCatching { Files.deleteIfExists(it.toPath()) } }
    }

    override fun sizeBytes(): Long = dir().listFiles().orEmpty().sumOf { it.length() }

    private fun dir() = File(cacheDir.get(), DIR)

    private fun fileOf(key: String): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir(), "$hash.nzb.gz")
    }

    companion object {
        const val DIR = "nntp_nzb"
        const val MAX_FILES = 40
        const val MAX_AGE_MILLIS = 14L * 24 * 60 * 60 * 1000
    }
}
