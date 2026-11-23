package com.seastreams.tv.usenet.stream

import com.seastreams.tv.usenet.nntp.SegmentFetch

/**
 * The segments kept downloading around the play head (ARCHITECTURE §10 Read-ahead). [advance] to index `i`
 * keeps `i - 1 .. i + windowSegments` and cancels everything else, except the pinned head (`< headPins`) and
 * tail (`>= tailFrom`): players return to a video's two ends (MKV Cues live at the end). A seek therefore
 * cancels the old window's queued downloads at once instead of letting them hold connections.
 */
class ReadAheadWindow(
    private val segmentCount: Int,
    val windowSegments: Int,
    private val headPins: Int,
    private val tailFrom: Int,
    private val start: (index: Int) -> SegmentFetch,
) {
    private val slots = HashMap<Int, SegmentFetch>()
    private var position = -1
    private var closed = false

    /** The fetch for [index]: the one in flight or done, else a new one. A cancelled or failed slot is replaced. */
    @Synchronized
    fun request(index: Int): SegmentFetch {
        val known = slots[index]
        if (known != null && !known.cancelled && known.failure == null) return known
        return start(index).also { if (!closed) slots[index] = it }
    }

    /** Forgets [index] (its decoded bytes were released): the next [request] starts afresh. */
    @Synchronized
    fun invalidate(index: Int) {
        slots.remove(index)
    }

    /** The reader is at [index]: shift the window there. */
    fun advance(index: Int) {
        val stale = ArrayList<SegmentFetch>()
        synchronized(this) {
            if (closed || index == position) return
            position = index
            val iterator = slots.entries.iterator()
            while (iterator.hasNext()) {
                val (slot, fetch) = iterator.next()
                if (slot in index - 1..index + windowSegments || pinned(slot)) continue
                iterator.remove()
                if (!fetch.isDone) stale += fetch
            }
            for (next in index + 1..minOf(segmentCount - 1, index + windowSegments)) {
                if (slots[next]?.let { !it.cancelled } != true) slots[next] = start(next)
            }
        }
        stale.forEach(SegmentFetch::cancel)
    }

    /**
     * The reader moved to another volume of the release: drop every unpinned slot (cancelling queued downloads)
     * so only the volume being read keeps a full window. The next [advance] rebuilds it from scratch.
     */
    fun idle() {
        val stale = ArrayList<SegmentFetch>()
        synchronized(this) {
            if (closed) return
            position = -1
            val iterator = slots.entries.iterator()
            while (iterator.hasNext()) {
                val (slot, fetch) = iterator.next()
                if (pinned(slot)) continue
                iterator.remove()
                if (!fetch.isDone) stale += fetch
            }
        }
        stale.forEach(SegmentFetch::cancel)
    }

    /** Starts the pinned tail downloading without waiting (called once the part size is known). */
    @Synchronized
    fun prefetchTail() {
        if (closed) return
        for (index in tailFrom.coerceAtLeast(0) until segmentCount) {
            if (index !in slots) slots[index] = start(index)
        }
    }

    @Synchronized
    fun activeIndices(): Set<Int> = slots.filterValues { !it.cancelled }.keys.toSet()

    fun close() {
        val all = synchronized(this) {
            closed = true
            slots.values.toList().also { slots.clear() }
        }
        all.filterNot(SegmentFetch::isDone).forEach(SegmentFetch::cancel)
    }

    private fun pinned(index: Int) = index < headPins || index >= tailFrom

    companion object {
        const val TAIL_BYTES = 5L * 1024 * 1024
        const val HEAD_PINS = 2
        const val HEAP_SHARE = 10

        /** The profile's read-ahead, clamped so the window holds at most a tenth of the heap (the arena's share). */
        fun windowFor(readAheadSegments: Int, partSize: Long, maxMemory: Long = Runtime.getRuntime().maxMemory()): Int {
            val byHeap = if (partSize > 0) (maxMemory / HEAP_SHARE / partSize).toInt() else readAheadSegments
            return minOf(readAheadSegments, byHeap).coerceAtLeast(1)
        }

        /** First index of the 5 MB tail pin. */
        fun tailFrom(segmentCount: Int, partSize: Long, tailBytes: Long = TAIL_BYTES): Int {
            if (partSize <= 0) return segmentCount
            val count = ((tailBytes + partSize - 1) / partSize).toInt()
            return (segmentCount - count).coerceAtLeast(0)
        }
    }
}
