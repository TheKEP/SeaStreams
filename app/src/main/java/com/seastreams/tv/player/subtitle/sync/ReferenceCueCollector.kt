package com.seastreams.tv.player.subtitle.sync

import androidx.media3.common.text.Cue
import com.seastreams.tv.player.subtitle.TimedCue
import kotlin.math.abs

/**
 * Whether anything is on screen in [cues]: text **or** a bitmap. Blu-ray remuxes carry PGS (bitmap)
 * subtitles whose cues have no text at all; counting text only would see every one of them as empty.
 */
fun hasVisibleCue(cues: List<Cue>): Boolean = cues.any { it.bitmap != null || !it.text.isNullOrBlank() }

/** What the reference (embedded) subtitle track has shown so far. */
class ReferenceSnapshot(val intervals: List<TimedCue>, val spans: List<ObservedSpan>)

/**
 * Records the timing of a background embedded subtitle track from ExoPlayer's `onCues` transitions
 * (`CueGroup.presentationTimeUs`): a non-empty group opens an interval, the next transition closes
 * it. Observed spans are tracked separately so cue-free stretches count as silence while seeks leave
 * unobserved holes. Events stamped more than [STALE_US] away from the playhead are dropped: cue callbacks
 * posted before a seek can arrive after `onPositionDiscontinuity` and would otherwise stretch a span
 * across the jump. Main thread writes, background reads.
 */
class ReferenceCueCollector {

    private val lock = Any()
    private val intervals = ArrayList<TimedCue>()
    private val spans = ArrayList<ObservedSpan>()
    private var openStartUs: Long? = null
    private var spanStartUs: Long? = null
    private var lastUs: Long? = null

    @Volatile
    var active: Boolean = false
        private set

    /** Starts (or stops) recording; either way the previous recording is dropped. */
    fun setActive(on: Boolean) = synchronized(lock) {
        active = on
        intervals.clear()
        spans.clear()
        openStartUs = null
        spanStartUs = null
        lastUs = null
    }

    fun onCues(presentationTimeUs: Long, nonEmpty: Boolean, playbackPositionUs: Long) = synchronized(lock) {
        if (!active || abs(presentationTimeUs - playbackPositionUs) > STALE_US) return@synchronized
        val previous = lastUs
        if (previous != null && presentationTimeUs < previous) closeSpan()
        val open = openStartUs
        if (open != null && presentationTimeUs - open in 1..MAX_CUE_US) intervals.add(TimedCue(open, presentationTimeUs, ""))
        openStartUs = if (nonEmpty) presentationTimeUs else null
        if (spanStartUs == null) spanStartUs = presentationTimeUs
        lastUs = presentationTimeUs
    }

    /** A seek: the open cue and the current span end where playback last was. */
    fun onDiscontinuity() = synchronized(lock) {
        closeSpan()
    }

    fun snapshot(): ReferenceSnapshot = synchronized(lock) {
        val current = spanStartUs?.let { start -> lastUs?.let { ObservedSpan(start, it) } }
        ReferenceSnapshot(intervals.toList(), spans + listOfNotNull(current))
    }

    private fun closeSpan() {
        val start = spanStartUs
        val end = lastUs
        if (start != null && end != null && end > start) spans.add(ObservedSpan(start, end))
        spanStartUs = null
        openStartUs = null
        lastUs = null
    }

    companion object {
        /** Longer "cues" are a missed transition, not dialogue. */
        private const val MAX_CUE_US = 10_000_000L

        /** How far a cue event may be from the playhead before it counts as left over from before a seek. */
        const val STALE_US = 2_000_000L
    }
}
