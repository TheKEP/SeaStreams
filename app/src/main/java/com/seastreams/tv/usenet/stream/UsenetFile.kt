package com.seastreams.tv.usenet.stream

import com.seastreams.tv.usenet.nntp.FetchCancelledException
import com.seastreams.tv.usenet.nntp.FetchPriority
import com.seastreams.tv.usenet.nntp.SegmentFetch
import com.seastreams.tv.usenet.nntp.SegmentSource
import com.seastreams.tv.usenet.nzb.NzbFile
import com.seastreams.tv.usenet.yenc.DecodedSegment
import java.io.Closeable
import java.io.IOException

/** The NZB file's articles disagree about where bytes are (a broken or mixed-up post). */
class SegmentLayoutException(message: String) : IOException(message)

/**
 * One posted file read as if it were local (ARCHITECTURE §10): [open] discovers the size from the first
 * article's `=ybegin size=` (or the last article's end when that is implausibly small); [read] at any position
 * finds the article through the [SegmentLocator], copies from it, and shifts the [ReadAheadWindow] there.
 * Decoded articles live in the shared [SegmentArena]. Blocking; an interrupted reader gets
 * [java.io.InterruptedIOException]. Thread-safe for one reader at a time (the Media3 loader).
 */
class UsenetFile(
    val file: NzbFile,
    private val source: SegmentSource,
    private val arena: SegmentArena,
    private val readAheadSegments: Int,
    private val segmentTimeoutMs: Long = 0,
    private val maxMemory: Long = Runtime.getRuntime().maxMemory(),
    private val priority: FetchPriority = FetchPriority.HIGH,
) : Closeable {
    private val segments = file.segments
    private val lock = Any()

    @Volatile
    private var closed = false
    private var locator: SegmentLocator? = null
    private var window: ReadAheadWindow? = null

    /** Total bytes, `-1` until [open]. */
    @Volatile
    var size: Long = -1
        private set

    /** `=ybegin name=` of the first article (obfuscated sets are ordered by it in Milestone 9). */
    var name: String = ""
        private set

    var partSize: Long = 0
        private set

    val opened: Boolean get() = size >= 0

    init {
        require(segments.isNotEmpty()) { "The NZB file lists no articles" }
    }

    fun open() {
        if (opened) return
        val first = load(0, standalone = true)
        if (first.begin != 0L || first.length <= 0) throw SegmentLayoutException("The first article doesn't start the file")
        var total = first.fileSize
        if (segments.size > 1 && total <= first.length) total = load(segments.size - 1, standalone = true).end
        synchronized(lock) {
            if (opened) return
            name = first.name
            partSize = first.length.toLong()
            install(total).record(0, first.begin, first.length)
            size = total
        }
    }

    /** Takes size and grid from [sibling], a same-size volume of one set, so a middle read needs no probe. */
    fun adoptGrid(sibling: UsenetFile) {
        sibling.open()
        synchronized(lock) {
            if (opened) return
            name = sibling.name
            partSize = sibling.partSize
            install(sibling.size).adoptGrid(sibling.partSize)
            size = sibling.size
        }
    }

    /** Copies up to [length] bytes at [position] into [target]; `-1` at the end of the file. */
    fun read(position: Long, target: ByteArray, offset: Int, length: Int): Int {
        open()
        if (position >= size) return -1
        if (length == 0) return 0
        val locate = checkNotNull(locator)
        var low = 0
        var high = segments.size - 1
        var index = locate.guess(position, low, high)
        repeat(MAX_PROBES) {
            val segment = load(index, standalone = false)
            locate.record(index, segment.begin, segment.length)
            when {
                position < segment.begin -> high = index - 1
                position >= segment.end -> low = index + 1
                else -> {
                    val count = minOf(length.toLong(), segment.end - position).toInt()
                    if (segment.copyTo((position - segment.begin).toInt(), target, offset, count)) {
                        window?.advance(index)
                        return count
                    }
                    window?.invalidate(index) // released under us: fetch it again
                    return@repeat
                }
            }
            if (low > high) throw SegmentLayoutException("The file's articles don't line up at byte $position")
            index = locate.guess(position, low, high)
        }
        throw SegmentLayoutException("Byte $position couldn't be located")
    }

    override fun close() {
        closed = true
        window?.close()
    }

    private fun install(total: Long): SegmentLocator {
        val created = SegmentLocator(segments.size, total)
        locator = created
        val tail = ReadAheadWindow.tailFrom(segments.size, partSize)
        window = ReadAheadWindow(
            segmentCount = segments.size,
            windowSegments = ReadAheadWindow.windowFor(readAheadSegments, partSize, maxMemory),
            headPins = ReadAheadWindow.HEAD_PINS,
            tailFrom = tail,
            start = ::start,
        ).also { it.prefetchTail() }
        return created
    }

    /** Starts [index]'s fetch; whatever arrives is handed to the arena, which owns it from then on. */
    private fun start(index: Int): SegmentFetch {
        val segment = segments[index]
        return source.fetch(segment.messageId, segment.number, priority).also { fetch ->
            fetch.onDone { done -> done.result?.let { arena.put(segment.messageId, it) } }
        }
    }

    private fun load(index: Int, standalone: Boolean): DecodedSegment {
        val id = segments[index].messageId
        repeat(RETRIES) {
            if (closed) throw IOException("The stream is closed")
            arena.get(id)?.let { return it }
            val fetch = if (standalone) start(index) else window?.request(index) ?: start(index)
            try {
                return arena.put(id, fetch.await(segmentTimeoutMs))
            } catch (e: FetchCancelledException) {
                window?.invalidate(index) // cancelled by a seek on another thread: ask again
            }
        }
        throw IOException("The article download kept being cancelled")
    }

    private companion object {
        const val MAX_PROBES = 64
        const val RETRIES = 3
    }
}
