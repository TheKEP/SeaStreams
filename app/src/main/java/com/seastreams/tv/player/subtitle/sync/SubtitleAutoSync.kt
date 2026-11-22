package com.seastreams.tv.player.subtitle.sync

import com.seastreams.tv.player.subtitle.TimedCue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** What auto-sync needs from the player. Called on the main thread. */
interface AutoSyncSource {
    /** Quietly plays embedded text track [id] as the timing reference (`null` turns it off again). */
    fun setReferenceTrack(id: String?)

    fun referenceSnapshot(): ReferenceSnapshot

    /** Turns the audio voice-activity tap on or off. */
    fun setSpeechCollecting(on: Boolean)

    val speechLog: SpeechEnergyLog
}

/** A detected sync: [offsetMs] (`+` shows subtitles earlier) on [rate]'s clock, found against [source]. */
data class SyncLock(val offsetMs: Long, val rate: SyncRate, val source: SyncSource)

/** Analysis cadence (virtual time in tests). */
data class AutoSyncTiming(
    /** While scanning: fast, so an early lock lands as soon as ~15 s / 5 lines of dialogue are in. */
    val scanIntervalMs: Long = 5_000,
    /** After an early lock: slower checks until a full-tier result confirms or refines it. */
    val refineIntervalMs: Long = 20_000,
    /** An embedded reference that hasn't locked by now hands over to audio. */
    val embeddedTimeoutMs: Long = 30_000,
    /** An embedded reference with fewer than `MIN_EMBEDDED_CUES` cues by now (signs-only, silent) is dropped. */
    val embeddedMinCueWindowMs: Long = 20_000,
    /** A same-language embedded reference kept past the handover (rate search, see [SubtitleAutoSync]) is released by now. */
    val embeddedRateTimeoutMs: Long = 120_000,
    val voiceTimeoutMs: Long = 900_000,
    val refineTimeoutMs: Long = 300_000,
    /** Audio analysed per pass: the last 5 minutes heard. */
    val voiceWindowCells: Int = 3_000,
    /** After the final lock: how often drift (e.g. 23.976 vs 24 fps) is re-checked. */
    val trackIntervalMs: Long = 60_000,
    /** Drift checks look at the last 2 minutes only, so they follow the local offset. */
    val trackWindowCells: Int = 1_200,
)

/**
 * Detects the sync offset and clock rate of one external subtitle track while it plays (PRD §9
 * Auto-Sync), without touching playback:
 *
 * 1. **Scan** every [AutoSyncTiming.scanIntervalMs]: against an embedded track (enabled in the
 *    background, never drawn; text or PGS bitmap cues) when there is one. A same-language reference locks
 *    on its first gated result, another language's on two agreeing ones. It hands over to audio voice
 *    activity with fewer than [MIN_EMBEDDED_CUES] cues after [AutoSyncTiming.embeddedMinCueWindowMs] or
 *    no lock after [AutoSyncTiming.embeddedTimeoutMs]; there two consecutive gated results must agree
 *    within `±0.2s`. A same-language reference that timed out (it had cues, so it isn't signs-only) stays on until
 *    [AutoSyncTiming.embeddedRateTimeoutMs] so its full-tier, rate-searching passes can still lock (a
 *    PAL-timed file can't lock within 30 s). The audio tap runs from the start.
 * 2. **Refine** an early lock every [AutoSyncTiming.refineIntervalMs] until a [GateTier.FULL] result
 *    confirms it; it is re-applied if it moved.
 *
 * Rates ([SyncRate]) are fitted by [RateFit] with a unit-rate prior: while scanning every
 * [RATE_SEARCH_EVERY]th pass, always when refining and tracking. Every gated offset is snapped to
 * dialogue onsets. After the final lock the offset is **tracked** every [AutoSyncTiming.trackIntervalMs]
 * on the last 2 minutes: two agreeing full-tier results 0.1–1.5 s away (or on another rate) re-centre
 * it, and [JUMP_CONFIRMATIONS] agreeing ones re-centre a larger jump (another cut, an inserted scene).
 *
 * [onStatus] reports Scanning → Locked (refining) → Locked (tracking), or NoMatch. A [start] with
 * `resume` (a cached result) applies it at once and goes straight to tracking. Offsets under `0.1s`
 * apply as `0.0s`. [stop] turns the reference track and the audio tap off at once.
 */
class SubtitleAutoSync(
    private val scope: CoroutineScope,
    private val source: AutoSyncSource,
    private val onDetected: (SyncLock) -> Unit,
    private val onStatus: (AutoSyncStatus) -> Unit = {},
    private val analysis: CoroutineDispatcher = Dispatchers.Default,
    private val timing: AutoSyncTiming = AutoSyncTiming(),
) {
    private var job: Job? = null

    val running: Boolean get() = job?.isActive == true

    /** One run's inputs: the external cues on every rate's clock, and the reference language. */
    private class Run(cues: List<TimedCue>, val referenceTrackId: String?, val otherLanguage: String?) {
        val clocks: Map<SyncRate, List<TimedCue>> = SyncRate.entries.associateWith { it.apply(cues) }
        val sameLanguage: Boolean get() = otherLanguage == null
    }

    /** A gated pass: offset on [rate]'s clock and the tier it passed. */
    private data class Gated(val offsetMs: Long, val rate: SyncRate, val tier: GateTier)

    private class Lock(val source: SyncSource, val gated: Gated)

    /** How an embedded scan ended: a [lock], or the reference [cues] received when it gave up ([timedOut] or too few). */
    private class EmbeddedScan(val lock: Lock?, val cues: Int, val timedOut: Boolean)

    /**
     * Starts detection for [cues]. [referenceTrackId]: the embedded reference, in [referenceLanguage]
     * when that isn't the external track's language. [resume]: a result found earlier this session.
     */
    fun start(cues: List<TimedCue>, referenceTrackId: String?, referenceLanguage: String? = null, resume: SyncLock? = null) {
        if (job != null) stop()
        val run = Run(SyncCueFilter.dialogue(cues).sortedBy { it.startUs }, referenceTrackId, referenceLanguage)
        job = scope.launch {
            source.setSpeechCollecting(true)
            if (resume != null) {
                val from = if (resume.source == SyncSource.EMBEDDED && referenceTrackId != null) SyncSource.EMBEDDED else SyncSource.AUDIO
                if (from == SyncSource.EMBEDDED) source.setReferenceTrack(referenceTrackId)
                val applied = apply(resume.copy(source = from))
                onStatus(locked(run, applied, refining = false, tracking = true))
                track(run, applied)
                return@launch
            }
            val lock = scan(run)
            if (lock == null) {
                cleanUp()
                onStatus(AutoSyncStatus.NoMatch)
                return@launch
            }
            if (lock.source == SyncSource.AUDIO) source.setReferenceTrack(null)
            var applied = apply(SyncLock(lock.gated.offsetMs, lock.gated.rate, lock.source))
            if (lock.gated.tier == GateTier.EARLY) {
                onStatus(locked(run, applied, refining = true))
                val refined = refine(run, applied)
                // The full-tier result is the more precise one: take it even when it moved by only 0.1 s.
                if (refined != null && refined != applied) applied = apply(refined)
            }
            onStatus(locked(run, applied, refining = false, tracking = true))
            track(run, applied)
        }
    }

    /** Cancels detection and turns the reference track and audio tap off at once. */
    fun stop() {
        job?.cancel()
        job = null
        cleanUp()
    }

    private suspend fun scan(run: Run): Lock? {
        var embeddedCues: Int? = null
        var keepEmbedded = false
        if (run.referenceTrackId != null) {
            val embedded = scanEmbedded(run, run.referenceTrackId)
            embedded.lock?.let { return it }
            embeddedCues = embedded.cues
            keepEmbedded = embedded.timedOut && run.sameLanguage
            if (!keepEmbedded) source.setReferenceTrack(null)
        }
        return scanVoice(run, embeddedCues, keepEmbedded)
    }

    /** Embedded scan until a lock, too few cues by 20 s, or the 30 s timeout. */
    private suspend fun scanEmbedded(run: Run, trackId: String): EmbeddedScan {
        onStatus(AutoSyncStatus.Scanning(SyncSource.EMBEDDED, 0))
        source.setReferenceTrack(trackId)
        var elapsed = 0L
        var received = 0
        var previous: Gated? = null
        while (elapsed < timing.embeddedTimeoutMs) {
            delay(timing.scanIntervalMs)
            elapsed += timing.scanIntervalMs
            val pass = analyze(run, SyncSource.EMBEDDED, SyncRate.UNIT, searchRates = false)
            received = pass.analysis.cues
            onStatus(pass.analysis.status(SyncSource.EMBEDDED))
            val result = pass.gated()
            if (result != null && (run.sameLanguage || agree(previous, result))) {
                return EmbeddedScan(Lock(SyncSource.EMBEDDED, both(previous, result)), received, timedOut = false)
            }
            previous = result
            if (elapsed >= timing.embeddedMinCueWindowMs && received < MIN_EMBEDDED_CUES) return EmbeddedScan(null, received, timedOut = false)
        }
        return EmbeddedScan(null, received, timedOut = true)
    }

    private suspend fun scanVoice(run: Run, embeddedCues: Int?, keepEmbedded: Boolean): Lock? {
        onStatus(AutoSyncStatus.Scanning(SyncSource.AUDIO, 0, embeddedCues = embeddedCues))
        var embeddedOn = keepEmbedded
        var previous: Gated? = null
        var elapsed = 0L
        var passes = 0
        while (elapsed < timing.voiceTimeoutMs) {
            delay(timing.scanIntervalMs)
            elapsed += timing.scanIntervalMs
            val search = passes++ % RATE_SEARCH_EVERY == 0 || (previous?.rate ?: SyncRate.UNIT) != SyncRate.UNIT
            if (embeddedOn && elapsed + timing.embeddedTimeoutMs > timing.embeddedRateTimeoutMs) {
                embeddedOn = false
                source.setReferenceTrack(null)
            }
            if (embeddedOn && search) {
                val embedded = analyze(run, SyncSource.EMBEDDED, SyncRate.UNIT, searchRates = true).gated()
                if (embedded?.tier == GateTier.FULL) return Lock(SyncSource.EMBEDDED, embedded)
            }
            val pass = analyze(run, SyncSource.AUDIO, SyncRate.UNIT, searchRates = search)
            onStatus(pass.analysis.status(SyncSource.AUDIO, embeddedCues))
            val result = pass.gated()
            if (result != null && agree(previous, result)) return Lock(SyncSource.AUDIO, both(previous, result))
            previous = result
        }
        return null
    }

    /** A full-tier lock confirming/refining an early one (audio or another language: two agreeing), or `null`. */
    private suspend fun refine(run: Run, lock: SyncLock): SyncLock? {
        var previous: Gated? = null
        var elapsed = 0L
        while (elapsed < timing.refineTimeoutMs) {
            delay(timing.refineIntervalMs)
            elapsed += timing.refineIntervalMs
            val full = analyze(run, lock.source, lock.rate).gated()?.takeIf { it.tier == GateTier.FULL }
            val single = lock.source == SyncSource.EMBEDDED && run.sameLanguage
            if (full != null && (single || agree(previous, full))) return SyncLock(full.offsetMs, full.rate, lock.source)
            previous = full
        }
        return null
    }

    /** Re-centres on the local offset while it drifts or jumps; runs until [stop] (manual nudge, track change, exit). */
    private suspend fun track(run: Run, initial: SyncLock) {
        var applied = initial
        val recent = ArrayDeque<Gated>()
        while (true) {
            delay(timing.trackIntervalMs)
            val local = analyze(run, applied.source, applied.rate, recent = true).gated()?.takeIf { it.tier == GateTier.FULL }
            if (local == null) {
                recent.clear()
                continue
            }
            recent.addLast(local)
            if (recent.size > JUMP_CONFIRMATIONS) recent.removeFirst()
            if (recent.size < 2 || !agree(recent[recent.size - 2], local)) continue
            val change = abs(local.offsetMs - applied.offsetMs)
            val jump = recent.size == JUMP_CONFIRMATIONS && recent.zipWithNext().all { (a, b) -> agree(a, b) }
            val move = local.rate != applied.rate || change in MIN_APPLIED_MS..MAX_TRACK_STEP_MS || (change > MAX_TRACK_STEP_MS && jump)
            if (!move) continue
            val rateChanged = local.rate != applied.rate
            applied = apply(SyncLock(local.offsetMs, local.rate, applied.source))
            if (rateChanged) onStatus(locked(run, applied, refining = false, tracking = true))
        }
    }

    private fun apply(lock: SyncLock): SyncLock {
        val shown = if (abs(lock.offsetMs) < MIN_APPLIED_MS) lock.copy(offsetMs = 0) else lock
        onDetected(shown)
        return shown
    }

    private fun locked(run: Run, lock: SyncLock, refining: Boolean, tracking: Boolean = false) = AutoSyncStatus.Locked(
        source = lock.source,
        refining = refining,
        tracking = tracking,
        rate = lock.rate,
        referenceLanguage = run.otherLanguage.takeIf { lock.source == SyncSource.EMBEDDED },
    )

    /** Two consecutive results on the same clock within `±0.2s`. */
    private fun agree(a: Gated?, b: Gated?): Boolean = a != null && b != null && a.rate == b.rate && abs(a.offsetMs - b.offsetMs) <= AGREEMENT_MS

    /** [current], on the full tier only when [previous] was too (or there was no previous one to agree with). */
    private fun both(previous: Gated?, current: Gated): Gated =
        if (previous == null || (previous.tier == GateTier.FULL && current.tier == GateTier.FULL)) current else current.copy(tier = GateTier.EARLY)

    private fun RatedAnalysis.gated(): Gated? = analysis.result?.let { Gated(it.offsetMs, rate, it.tier) }

    /** One pass against [from] on [rate] (searching other rates when [RateFit] allows); [recent]: last 2 minutes. */
    private suspend fun analyze(run: Run, from: SyncSource, rate: SyncRate, recent: Boolean = false, searchRates: Boolean = true): RatedAnalysis =
        when (from) {
            SyncSource.EMBEDDED -> {
                val reference = source.referenceSnapshot()
                val windowUs = if (recent) cellStartUs(timing.trackWindowCells.toLong()) else null
                withContext(analysis) {
                    RateFit.best(rate, searchRates) { SyncAnalyzer.analyzeEmbedded(run.clocks.getValue(it), reference, windowUs) }
                }
            }
            SyncSource.AUDIO -> {
                val cells = if (recent) timing.trackWindowCells else timing.voiceWindowCells
                withContext(analysis) { RateFit.best(rate, searchRates) { SyncAnalyzer.analyzeVoice(run.clocks.getValue(it), source.speechLog, cells) } }
            }
        }

    private fun cleanUp() {
        source.setReferenceTrack(null)
        source.setSpeechCollecting(false)
    }

    companion object {
        const val MIN_APPLIED_MS = 100L
        const val AGREEMENT_MS = 200L

        /** A drift re-centre larger than this needs [JUMP_CONFIRMATIONS] agreeing passes (a jump, not drift). */
        const val MAX_TRACK_STEP_MS = 1_500L
        const val JUMP_CONFIRMATIONS = 3

        /** An embedded reference needs at least this many cues within the first 20 s to stay in use. */
        const val MIN_EMBEDDED_CUES = 5

        /** While scanning, other frame rates are tried on every 4th pass (20 s) to bound CPU. */
        const val RATE_SEARCH_EVERY = 4
    }
}
