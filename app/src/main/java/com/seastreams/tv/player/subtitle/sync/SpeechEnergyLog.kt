package com.seastreams.tv.player.subtitle.sync

/**
 * The last [capacity] cells (10 minutes) of speech measurements, keyed by media-time cell. The audio
 * tap writes from the playback thread; auto-sync reads snapshots from a background coroutine. Seeks
 * simply land in other cells, so holes stay uncovered.
 */
class SpeechEnergyLog(private val capacity: Int = DEFAULT_CAPACITY) {

    private val cells = LongArray(capacity) { NO_CELL }
    private val bandDb = FloatArray(capacity)
    private val ratio = FloatArray(capacity)
    private val lock = Any()
    private var latest = NO_CELL

    fun record(cell: Long, voiceBandDb: Float, voiceRatio: Float) = synchronized(lock) {
        val slot = slot(cell)
        cells[slot] = cell
        bandDb[slot] = voiceBandDb
        ratio[slot] = voiceRatio
        latest = cell
    }

    /**
     * The cell logged last (where playback is now), or `null` before any. Not the highest cell ever
     * logged: after a seek back the analysis window must follow the playhead.
     */
    fun latestCell(): Long? = synchronized(lock) { latest.takeIf { it != NO_CELL } }

    /** The logged cells in `fromCell until toCell` (at most [capacity] of them). */
    fun snapshot(fromCell: Long, toCell: Long): SpeechSnapshot = synchronized(lock) {
        val start = maxOf(fromCell, toCell - capacity)
        val size = (toCell - start).coerceAtLeast(0).toInt()
        val db = FloatArray(size)
        val voice = FloatArray(size)
        val covered = BooleanArray(size)
        for (i in 0 until size) {
            val cell = start + i
            val slot = slot(cell)
            if (cells[slot] == cell) {
                covered[i] = true
                db[i] = bandDb[slot]
                voice[i] = ratio[slot]
            }
        }
        SpeechSnapshot(start, db, voice, covered)
    }

    fun clear() = synchronized(lock) {
        cells.fill(NO_CELL)
        latest = NO_CELL
    }

    private fun slot(cell: Long): Int = Math.floorMod(cell, capacity.toLong()).toInt()

    companion object {
        const val DEFAULT_CAPACITY = 6_000
        private const val NO_CELL = Long.MIN_VALUE
    }
}
