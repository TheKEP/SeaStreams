package com.seastreams.tv.usenet.cache

import com.seastreams.tv.core.cache.CacheDirProvider
import com.seastreams.tv.core.cache.ManagedCache
import com.seastreams.tv.core.util.daemonExecutor
import com.seastreams.tv.core.util.sha1Hex
import com.seastreams.tv.usenet.yenc.DecodedSegment
import com.seastreams.tv.usenet.yenc.SegmentBufferPool
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The bounded Usenet segment cache (ARCHITECTURE §3): one file per SHA-1(message ID) in `cacheDir/nntp_segments`,
 * written through in the background (writes are dropped when [maxPending] are already queued, so it never slows
 * playback). The cap is the smallest of the profile value ([profileCapBytes]), 25% of the space the cache may use
 * (free + its own), and whatever leaves 2 GB free; every write trims to it. [clear] drops the index at once and
 * deletes the files; readers already open keep their handles (files are opened with NIO, which allows deletion
 * while open, on Windows too) and in-flight writes from before the clear are discarded.
 */
@Singleton
class SegmentDiskCache internal constructor(
    private val cacheDir: CacheDirProvider,
    private val freeSpace: (File) -> Long,
    private val writer: Executor,
    private val maxPending: Int,
) : ManagedCache {

    @Inject
    constructor(cacheDir: CacheDirProvider) : this(cacheDir, File::getUsableSpace, defaultWriter(), MAX_PENDING)

    override val dirName: String = DIR

    /** The performance profile's cap; `0` turns the cache off. Set by the engine's configuration. */
    @Volatile
    var profileCapBytes: Long = 1_024L * MB

    private val index = SegmentCacheIndex()
    private val pending = AtomicInteger()
    private var generation = 0
    private var loaded = false

    /** The cap now: profile ∩ 25% of usable space ∩ usable space − 2 GB. */
    fun capBytes(): Long = synchronized(this) { capLocked() }

    /** Bytes currently indexed. */
    fun indexedBytes(): Long = synchronized(this) { loadLocked(); index.totalBytes }

    fun get(messageId: String, pool: SegmentBufferPool, expectedPart: Int? = null): DecodedSegment? {
        if (profileCapBytes <= 0) return null
        val key = keyOf(messageId)
        val file = synchronized(this) {
            loadLocked()
            if (!index.touch(key)) return null
            File(dir(), key)
        }
        val read: DecodedSegment? = try {
            Files.newByteChannel(file.toPath(), StandardOpenOption.READ).use { read(it, pool) }
        } catch (e: IOException) {
            null
        }
        if (read == null) {
            synchronized(this) { index.remove(key) }
            return null
        }
        val segment: DecodedSegment = read
        if (expectedPart != null && segment.part != null && segment.part != expectedPart) {
            segment.release()
            return null
        }
        return segment
    }

    /** Queues [segment] for writing; dropped when the writer is behind or the cache is off. */
    fun putAsync(messageId: String, segment: DecodedSegment) {
        if (profileCapBytes <= 0) return
        if (pending.incrementAndGet() > maxPending) {
            pending.decrementAndGet()
            return
        }
        val startedIn = synchronized(this) { generation }
        try {
            writer.execute {
                try {
                    write(messageId, segment, startedIn)
                } finally {
                    pending.decrementAndGet()
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            pending.decrementAndGet()
        }
    }

    override fun clear() {
        synchronized(this) {
            generation++
            index.clear()
            loaded = true
        }
        dir().listFiles().orEmpty().forEach { runCatching { Files.deleteIfExists(it.toPath()) } }
    }

    override fun sizeBytes(): Long = dir().listFiles().orEmpty().sumOf { it.length() }

    private fun write(messageId: String, segment: DecodedSegment, startedIn: Int) {
        synchronized(this) { loadLocked() }
        val key = keyOf(messageId)
        val dir = dir().apply { mkdirs() }
        val temp = File(dir, "$key.$TEMP_SUFFIX")
        val written = try {
            DataOutputStream(BufferedOutputStream(FileOutputStream(temp), 64 * 1024)).use { out ->
                writeHeader(out, segment)
                segment.writeTo(out)
            }
        } catch (e: IOException) {
            false
        }
        val size = temp.length()
        val evicted = synchronized(this) {
            if (!written || startedIn != generation || size > capLocked()) {
                temp.delete()
                return
            }
            try {
                Files.move(temp.toPath(), File(dir, key).toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (e: IOException) {
                temp.delete()
                return
            }
            index.add(key, size)
            index.evictTo(capLocked())
        }
        evicted.forEach { runCatching { Files.deleteIfExists(File(dir, it).toPath()) } }
    }

    private fun capLocked(): Long {
        val usable = freeSpace(dir().apply { mkdirs() }) + index.totalBytes
        return minOf(profileCapBytes, usable / 4, usable - MIN_FREE_BYTES).coerceAtLeast(0)
    }

    /** First use: index what an earlier run left (oldest first), drop its temp files, trim to the cap. */
    private fun loadLocked() {
        if (loaded) return
        loaded = true
        val files = dir().listFiles().orEmpty()
        val staleBefore = System.currentTimeMillis() - STALE_TEMP_MILLIS
        files.filter { it.name.endsWith(".$TEMP_SUFFIX") && it.lastModified() < staleBefore }.forEach { it.delete() }
        files.filter { it.isFile && !it.name.endsWith(".$TEMP_SUFFIX") }
            .sortedBy { it.lastModified() }
            .forEach { index.add(it.name, it.length()) }
        index.evictTo(capLocked()).forEach { File(dir(), it).delete() }
    }

    private fun dir() = File(cacheDir.get(), DIR)

    private fun writeHeader(out: DataOutputStream, segment: DecodedSegment) {
        val name = segment.name.toByteArray(Charsets.UTF_8).let { if (it.size > MAX_NAME) it.copyOf(MAX_NAME) else it }
        out.writeInt(MAGIC)
        out.writeLong(segment.begin)
        out.writeLong(segment.fileSize)
        out.writeInt(segment.part ?: -1)
        out.writeInt(segment.totalParts ?: -1)
        out.writeInt(segment.length)
        out.writeShort(name.size)
        out.write(name)
    }

    private fun read(channel: SeekableByteChannel, pool: SegmentBufferPool): DecodedSegment? {
        val fixed = ByteBuffer.allocate(FIXED_HEADER)
        if (!fill(channel, fixed)) return null
        fixed.flip()
        if (fixed.int != MAGIC) return null
        val begin = fixed.long
        val fileSize = fixed.long
        val part = fixed.int.takeIf { it >= 0 }
        val total = fixed.int.takeIf { it >= 0 }
        val length = fixed.int
        val nameLength = fixed.short.toInt()
        if (length <= 0 || length > MAX_SEGMENT || nameLength !in 0..MAX_NAME) return null
        val nameBuffer = ByteBuffer.allocate(nameLength)
        if (!fill(channel, nameBuffer)) return null
        val data = pool.acquire(length)
        val filled = try {
            fill(channel, ByteBuffer.wrap(data, 0, length))
        } catch (e: IOException) {
            false
        }
        if (!filled) {
            pool.recycle(data)
            return null
        }
        return DecodedSegment(data, length, begin, fileSize, String(nameBuffer.array(), Charsets.UTF_8), part, total, pool)
    }

    private fun fill(channel: SeekableByteChannel, buffer: ByteBuffer): Boolean {
        while (buffer.hasRemaining()) if (channel.read(buffer) < 0) return false
        return true
    }

    companion object {
        const val DIR = "nntp_segments"
        private const val MB = 1024L * 1024
        const val MIN_FREE_BYTES = 2_048L * MB
        const val MAX_PENDING = 16
        private const val TEMP_SUFFIX = "tmp"
        private const val STALE_TEMP_MILLIS = 60_000L
        private const val MAGIC = 0x53534731 // "SSG1"
        private const val FIXED_HEADER = 4 + 8 + 8 + 4 + 4 + 4 + 2
        private const val MAX_NAME = 255
        private const val MAX_SEGMENT = 16 * 1024 * 1024

        fun keyOf(messageId: String): String = sha1Hex(messageId)

        private fun defaultWriter(): Executor = daemonExecutor(1, "nntp-cache-writer")
    }
}
