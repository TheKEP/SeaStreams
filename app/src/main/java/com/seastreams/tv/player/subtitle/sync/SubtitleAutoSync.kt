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
 * Detects the sync offset of one external subtitle track while it plays (PRD §9 Auto-Sync), without
 * touching playback:
 *
 * 1. **Scan** every [AutoSyncTiming.scanIntervalMs]: against a same-language embedded track (enabled in
 *    the background, never drawn; text or PGS bitmap cues) when there is one, where the first gated
 *    result locks. It hands over to audio voice activity when it has fewer than [MIN_EMBEDDED_CUES] cues
 *    after [AutoSyncTiming.embeddedMinCueWindowMs] or no lock after [AutoSyncTiming.embeddedTimeoutMs];
 *    there two consecutive gated results must agree within `±0.2s`. The audio tap runs from the start,
 *    so a handover finds speech already logged. The [GateTier.EARLY] tier locks from ~15 s / 5 cues.
 * 2. **Refine** an early lock every [AutoSyncTiming.refineIntervalMs] until a [GateTier.FULL] result
 *    confirms it; it is re-applied if it moved.
 *
 * Every gated offset is snapped to dialogue onsets by [OnsetRefiner]. After the final lock the offset is
 * **tracked**: every [AutoSyncTiming.trackIntervalMs] the last 2 minutes are re-analysed, and two
 * agreeing full-tier results that differ by 0.1–1.5 s re-centre it, so a file drifting against the
 * video (23.976 vs 24 fps) stays in sync.
 *
 * [onStatus] reports Scanning → Locked (refining) → Locked (tracking), or NoMatch. Offsets under `0.1s` apply as
 * `0.0s`. Nothing is applied when no estimate is confident enough. [stop] turns the reference track and
 * the audio tap off at once.
 */
class SubtitleAutoSync(
    private val scope: CoroutineScope,
    private val source: AutoSyncSource,
    private val onDetected: (offsetMs: Long) -> Unit,
    private val onStatus: (AutoSyncStatus) -> Unit = {},
    private val analysis: CoroutineDispatcher = Dispatchers.Default,
    private val timing: AutoSyncTiming = AutoSyncTiming(),
) {
    private var job: Job? = null

    val running: Boolean get() = job?.isActive == true

    private class Lock(val source: SyncSource, val result: GateResult)

    fun start(cues: List<TimedCue>, referenceTrackId: String?) {
        if (job != null) stop()
        val sorted = cues.sortedBy { it.startUs }
        job = scope.launch {
            source.setSpeechCollecting(true)
            var embeddedCues: Int? = null
            val lock = referenceTrackId?.let { id -> scanEmbedded(sorted, id) { embeddedCues = it } } ?: scanVoice(sorted, embeddedCues)
            if (lock == null) {
                cleanUp()
                onStatus(AutoSyncStatus.NoMatch)
                return@launch
            }
            var applied = apply(lock.result.offsetMs)
            if (lock.result.tier == GateTier.EARLY) {
                onStatus(AutoSyncStatus.Locked(lock.source, refining = true))
                val refined = refine(sorted, lock.source)
                // The full-tier result is the more precise one: take it even when it moved by only 0.1 s.
                if (refined != null && refined != applied) applied = apply(refined)
            }
            onStatus(AutoSyncStatus.Locked(lock.source, refining = false, tracking = true))
            track(sorted, lock.source, applied)
        }
    }

    /** Re-centres on the local offset while it drifts; runs until [stop] (manual nudge, track change, exit). */
    private suspend fun track(cues: List<TimedCue>, from: SyncSource, initial: Long) {
        var applied = initial
        var previous: Long? = null
        while (true) {
            delay(timing.trackIntervalMs)
            val local = analyze(cues, from, recent = true).result?.takeIf { it.tier == GateTier.FULL }?.offsetMs
            val before = previous
            previous = local
            if (local == null || before == null || abs(local - before) > AGREEMENT_MS) continue
            val change = abs(local - applied)
            if (change >= MIN_APPLIED_MS && change <= MAX_TRACK_STEP_MS) applied = apply(local)
        }
    }

    /** Cancels detection and turns the reference track and audio tap off at once. */
    fun stop() {
        job?.cancel()
        job = null
        cleanUp()
    }

    private fun apply(offsetMs: Long): Long {
        val shown = if (abs(offsetMs) < MIN_APPLIED_MS) 0 else offsetMs
        onDetected(shown)
        return shown
    }

    /** Embedded scan; [onGaveUp] gets the cue count when it hands over to audio. */
    private suspend fun scanEmbedded(cues: List<TimedCue>, trackId: String, onGaveUp: (Int) -> Unit): Lock? {
        onStatus(AutoSyncStatus.Scanning(SyncSource.EMBEDDED, 0))
        source.setReferenceTrack(trackId)
        var elapsed = 0L
        var received = 0
        while (elapsed < timing.embeddedTimeoutMs) {
            delay(timing.scanIntervalMs)
            elapsed += timing.scanIntervalMs
            val pass = analyze(cues, SyncSource.EMBEDDED)
            received = pass.cues
            onStatus(pass.status(SyncSource.EMBEDDED))
            pass.result?.let { return Lock(SyncSource.EMBEDDED, it) }
            if (elapsed >= timing.embeddedMinCueWindowMs && received < MIN_EMBEDDED_CUES) break
        }
        source.setReferenceTrack(null)
        onGaveUp(received)
        return null
    }

    private suspend fun scanVoice(cues: List<TimedCue>, embeddedCues: Int?): Lock? {
        onStatus(AutoSyncStatus.Scanning(SyncSource.AUDIO, 0, embeddedCues = embeddedCues))
        var previous: GateResult? = null
        var elapsed = 0L
        while (elapsed < timing.voiceTimeoutMs) {
            delay(timing.scanIntervalMs)
            elapsed += timing.scanIntervalMs
            val pass = analyze(cues, SyncSource.AUDIO)
            onStatus(pass.status(SyncSource.AUDIO, embeddedCues))
            val result = pass.result
            val before = previous
            if (result != null && before != null && abs(result.offsetMs - before.offsetMs) <= AGREEMENT_MS) {
                val tier = if (result.tier == GateTier.FULL && before.tier == GateTier.FULL) GateTier.FULL else GateTier.EARLY
                return Lock(SyncSource.AUDIO, result.copy(tier = tier))
            }
            previous = result
        }
        return null
    }

    /** A full-tier offset confirming/refining an early lock (audio: two agreeing), or `null`. */
    private suspend fun refine(cues: List<TimedCue>, from: SyncSource): Long? {
        var previous: Long? = null
        var elapsed = 0L
        while (elapsed < timing.refineTimeoutMs) {
            delay(timing.refineIntervalMs)
            elapsed += timing.refineIntervalMs
            val full = analyze(cues, from).result?.takeIf { it.tier == GateTier.FULL }?.offsetMs
            if (full != null && from == SyncSource.EMBEDDED) return full
            val before = previous
            if (full != null && before != null && abs(full - before) <= AGREEMENT_MS) return full
            previous = full
        }
        return null
    }

    /** One pass against [from]; [recent] limits it to the last 2 minutes (drift tracking). */
    private suspend fun analyze(cues: List<TimedCue>, from: SyncSource, recent: Boolean = false): SyncAnalysis = when (from) {
        SyncSource.EMBEDDED -> {
            val reference = source.referenceSnapshot()
            val windowUs = if (recent) cellStartUs(timing.trackWindowCells.toLong()) else null
            withContext(analysis) { SyncAnalyzer.analyzeEmbedded(cues, reference, windowUs) }
        }
        SyncSource.AUDIO -> {
            val cells = if (recent) timing.trackWindowCells else timing.voiceWindowCells
            withContext(analysis) { SyncAnalyzer.analyzeVoice(cues, source.speechLog, cells) }
        }
    }

    private fun cleanUp() {
        source.setReferenceTrack(null)
        source.setSpeechCollecting(false)
    }

    companion object {
        const val MIN_APPLIED_MS = 100L
        const val AGREEMENT_MS = 200L

        /** A drift re-centre larger than this is not drift (a different cut, a bad pass): ignored. */
        const val MAX_TRACK_STEP_MS = 1_500L

        /** An embedded reference needs at least this many cues within the first 20 s to stay in use. */
        const val MIN_EMBEDDED_CUES = 5
    }
}
