package com.seastreams.tv.usenet.verify

import com.seastreams.tv.domain.usenet.DamagePolicy
import com.seastreams.tv.usenet.nntp.NntpFuture
import com.seastreams.tv.usenet.nntp.monotonicMillis
import com.seastreams.tv.usenet.nzb.NzbFile

/** What a census has found so far. [complete] = every article was asked about (or the verdict is already final). */
data class CensusReport(
    val verdict: DamageVerdict,
    val missing: Map<Int, List<Int>>,
    val checked: Int,
    val total: Int,
    val complete: Boolean,
)

/**
 * The census (PRD §12 *Verify mode*): STAT for every article of the files that will be read, on pooled LOW-priority
 * connections through the normal failover ([com.seastreams.tv.usenet.nntp.NntpEngine.stat]), at most
 * [concurrency] in flight and for at most [maxLifetimeMs]. First articles go first, then a widening stride
 * (every 64th, 32nd, …), so damage anywhere shows up early. It stops as soon as the [policy] calls the release
 * broken. A STAT that fails (timeout, no provider) counts as unknown, never as missing.
 */
class CensusVerifier(
    private val stat: (messageId: String) -> NntpFuture<Boolean>,
    private val concurrency: Int,
    private val maxLifetimeMs: Long,
    private val policy: DamagePolicy,
    private val clock: () -> Long = monotonicMillis,
) {
    fun start(files: List<NzbFile>): Census = Census(files).also { it.pump() }

    inner class Census internal constructor(files: List<NzbFile>) {
        private val lock = Object()
        private val counts = files.indices.associateWith { files[it].segments.size }
        private val queue = files.flatMapIndexed { f, file -> file.segments.indices.map { Triple(f, it, file.segments[it].messageId) } }
            .sortedWith(compareBy({ order(it.second) }, { it.first }, { it.second }))
        private val missing = HashMap<Int, MutableList<Int>>()
        private val inFlight = HashSet<NntpFuture<Boolean>>()
        private val listeners = ArrayList<(CensusReport) -> Unit>()
        private val startedAt = clock()
        private var next = 0
        private var pumping = false
        private var answered = 0
        private var final: CensusReport? = null

        val total: Int get() = queue.size

        /** The final report, or `null` while it runs. */
        val report: CensusReport? get() = synchronized(lock) { final }

        /** Waits up to [timeoutMs] (`0` = don't wait) for the final report; `null` if it isn't in yet. */
        fun await(timeoutMs: Long): CensusReport? {
            val deadline = System.nanoTime() + timeoutMs * NANOS_PER_MILLI
            synchronized(lock) {
                while (final == null) {
                    val left = (deadline - System.nanoTime()) / NANOS_PER_MILLI
                    if (left <= 0) return null
                    lock.wait(left)
                }
                return final
            }
        }

        /** [listener] runs once with the final report (at once if it is already in). */
        fun onFinished(listener: (CensusReport) -> Unit) {
            val done = synchronized(lock) { final ?: run { listeners += listener; return } }
            listener(done)
        }

        fun cancel() = finish(complete = false)

        /**
         * Issues STATs until [concurrency] are in flight. Answers that arrive synchronously re-enter here; [pumping]
         * turns that into a no-op (this loop sees the freed slot), so the stack never grows with the article count.
         */
        internal fun pump() {
            synchronized(lock) {
                if (pumping) return
                pumping = true
            }
            while (true) {
                var expired = false
                val item = synchronized(lock) {
                    when {
                        final != null -> null
                        clock() - startedAt > maxLifetimeMs -> null.also { expired = true }
                        next < queue.size && inFlight.size < concurrency -> queue[next++]
                        else -> null
                    }.also { if (it == null) pumping = false }
                }
                if (item == null) {
                    if (expired) finish(complete = false)
                    return
                }
                val (file, index, id) = item
                val future = stat(id)
                synchronized(lock) { if (!future.isDone) inFlight += future }
                future.onDone { done -> answer(file, index, done) }
            }
        }

        private fun answer(file: Int, index: Int, done: NntpFuture<Boolean>) {
            val broken = synchronized(lock) {
                inFlight -= done
                if (final != null) return
                answered++
                if (done.result == false) missing.getOrPut(file) { ArrayList() } += index
                policy.judge(missing, counts) is DamageVerdict.Broken
            }
            when {
                broken -> finish(complete = true)
                synchronized(lock) { answered >= queue.size } -> finish(complete = true)
                else -> pump()
            }
        }

        private fun finish(complete: Boolean) {
            val (report, waiting, pending) = synchronized(lock) {
                if (final != null) return
                val snapshot = missing.mapValues { it.value.sorted() }
                val report = CensusReport(policy.judge(snapshot, counts), snapshot, answered, queue.size, complete)
                final = report
                lock.notifyAll()
                Triple(report, ArrayList(listeners).also { listeners.clear() }, ArrayList(inFlight).also { inFlight.clear() })
            }
            pending.forEach { it.cancel() }
            waiting.forEach { it(report) }
        }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val MAX_STRIDE_SHIFT = 20

        /** 0 first, then indices divisible by the highest powers of two (a widening stride), then the rest. */
        fun order(index: Int): Int = if (index == 0) Int.MIN_VALUE else -minOf(Integer.numberOfTrailingZeros(index), MAX_STRIDE_SHIFT)
    }
}
