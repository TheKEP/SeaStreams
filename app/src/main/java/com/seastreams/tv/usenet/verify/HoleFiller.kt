package com.seastreams.tv.usenet.verify

import com.seastreams.tv.domain.usenet.DamagePolicy
import com.seastreams.tv.usenet.nntp.ArticleNotFoundException
import com.seastreams.tv.usenet.nntp.FetchPriority
import com.seastreams.tv.usenet.nntp.SegmentFetch
import com.seastreams.tv.usenet.nntp.SegmentSource
import com.seastreams.tv.usenet.yenc.DecodedSegment

/** Where a missing article sits in its posted file, once that file's layout is known. */
data class HoleSlot(
    val file: Int,
    val article: Int,
    val begin: Long,
    val length: Int,
    val fileSize: Long,
    val name: String,
    val totalParts: Int,
)

/**
 * Tolerant damage handling (PRD §12, ARCHITECTURE §10 Damage): a missing article is replaced by filler of its exact
 * size — zeros, or an EBML Void element for Matroska ([MatroskaHoleRepair]) so the demuxer skips it — as long as the
 * release stays within ≤ [MAX_CONSECUTIVE] consecutive, ≤ [MAX_TOTAL] in all and ≤ 2% of its articles.
 */
object HoleFiller {
    const val MAX_CONSECUTIVE = 4
    const val MAX_TOTAL = 64
    private const val MAX_PERCENT = 2

    fun withinLimits(missing: Map<Int, Collection<Int>>, totalArticles: Int): Boolean {
        val holes = missing.values.sumOf { it.size }
        if (holes > MAX_TOTAL || holes * 100L > totalArticles * MAX_PERCENT.toLong()) return false
        return missing.values.all { longestRun(it) <= MAX_CONSECUTIVE }
    }

    fun longestRun(indices: Collection<Int>): Int {
        var best = 0
        var run = 0
        var previous = Int.MIN_VALUE
        for (i in indices.toSortedSet()) {
            run = if (i == previous + 1) run + 1 else 1
            best = maxOf(best, run)
            previous = i
        }
        return best
    }

    /** Filler for one hole: an EBML Void for Matroska, else zeros. */
    fun filler(length: Int, matroska: Boolean): ByteArray = if (matroska) MatroskaHoleRepair.void(length) else ByteArray(length)
}

/** The holes filled while one session plays, checked against [HoleFiller.withinLimits] before each new one. */
class HoleBudget(private val totalArticles: Int) {
    private val filled = HashMap<Int, MutableSet<Int>>()

    @Synchronized
    fun tryAdd(file: Int, article: Int): Boolean {
        if (filled[file]?.contains(article) == true) return true
        val trial = filled.mapValues { it.value.toMutableSet() }.toMutableMap()
        trial.getOrPut(file) { HashSet() } += article
        if (!HoleFiller.withinLimits(trial, totalArticles)) return false
        filled.getOrPut(file) { HashSet() } += article
        return true
    }

    @Synchronized
    fun count(): Int = filled.values.sumOf { it.size }
}

/**
 * Wraps the segment source of one session: an article that no provider has (`ArticleNotFoundException` with
 * `allProviders`) becomes filler under the Tolerant policy when [slot] knows where it sits and the [budget] allows
 * it; anything else fails as before (Strict: the read fails and failover moves on).
 */
class HoleFillingSource(
    private val inner: SegmentSource,
    private val policy: DamagePolicy,
    private val budget: HoleBudget,
    private val slot: (messageId: String) -> HoleSlot?,
    private val matroska: () -> Boolean,
) : SegmentSource {
    override fun fetch(messageId: String, part: Int, priority: FetchPriority): SegmentFetch {
        val upstream = inner.fetch(messageId, part, priority)
        if (policy != DamagePolicy.TOLERANT) return upstream
        val outer = SegmentFetch()
        upstream.onDone { done ->
            val result = done.result
            val failure = done.failure
            when {
                result != null -> if (!outer.complete(result)) result.release()
                failure is ArticleNotFoundException && failure.allProviders -> {
                    val hole = slot(messageId)
                    if (hole != null && budget.tryAdd(hole.file, hole.article)) outer.complete(synthetic(hole)) else outer.fail(failure)
                }
                failure != null -> outer.fail(failure)
            }
        }
        outer.onDone { if (it.cancelled) upstream.cancel() }
        return outer
    }

    private fun synthetic(hole: HoleSlot) = DecodedSegment(
        HoleFiller.filler(hole.length, matroska()),
        hole.length,
        hole.begin,
        hole.fileSize,
        hole.name,
        hole.article + 1,
        hole.totalParts,
    )
}

/**
 * Matroska hole repair (PRD §12): the hole becomes one EBML Void element (ID `0xEC`) whose size covers it exactly,
 * so a demuxer that lands on it skips to the next element instead of reading garbage.
 */
object MatroskaHoleRepair {
    private const val VOID_ID = 0xEC.toByte()
    private const val LONG_SIZE = 8

    fun void(length: Int): ByteArray {
        val out = ByteArray(length)
        if (length < 2) return out
        out[0] = VOID_ID
        val sizeBytes = if (length - 1 >= 1 + LONG_SIZE) LONG_SIZE else length - 1
        val payload = (length - 1 - sizeBytes).toLong()
        // A `sizeBytes`-long EBML vint: a marker bit at position `sizeBytes`, then the value big-endian.
        for (i in 0 until sizeBytes) {
            out[1 + i] = (payload ushr (8 * (sizeBytes - 1 - i))).toByte()
        }
        out[1] = (out[1].toInt() or (0x80 ushr (sizeBytes - 1))).toByte()
        return out
    }
}
