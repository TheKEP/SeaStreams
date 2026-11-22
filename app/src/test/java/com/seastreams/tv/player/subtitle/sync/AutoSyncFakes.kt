package com.seastreams.tv.player.subtitle.sync

import com.seastreams.tv.player.subtitle.TimedCue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope

/** A fake player started at [playFromUs] that has heard everything up to there + the virtual time elapsed. */
internal class FakeAutoSyncSource(private val scope: TestScope, private val embedded: List<TimedCue>?, private val playFromUs: Long) : AutoSyncSource {
    val referenceCalls = mutableListOf<String?>()
    val collectingCalls = mutableListOf<Boolean>()
    override val speechLog = SpeechEnergyLog()
    private val createdAtMs = scope.testScheduler.currentTime

    /** Media time of the playhead now (playback started when this source was created). */
    val nowUs: Long get() = playFromUs + (scope.testScheduler.currentTime - createdAtMs) * 1_000

    override fun setReferenceTrack(id: String?) {
        referenceCalls += id
    }

    override fun referenceSnapshot(): ReferenceSnapshot {
        val cues = embedded ?: return ReferenceSnapshot(emptyList(), emptyList())
        val now = nowUs
        return ReferenceSnapshot(cues.filter { it.startUs >= playFromUs && it.endUs <= now }, listOf(ObservedSpan(playFromUs, now)))
    }

    override fun setSpeechCollecting(on: Boolean) {
        collectingCalls += on
    }

    /** The audio tap logging one 100 ms cell per 100 ms of playback: voiced (-30 dB) during [speech]. */
    fun hear(speech: List<TimedCue>) {
        val grid = ActivityGrid.fromCues(speech, 0, MAX_CELLS)
        scope.backgroundScope.launch {
            var cell = cellOf(playFromUs)
            while (true) {
                delay(100)
                if (grid.active[cell.toInt()]) speechLog.record(cell, -30f, 0.8f) else speechLog.record(cell, -60f, 0.2f)
                cell++
            }
        }
    }

    private companion object {
        const val MAX_CELLS = 40_000
    }
}

/** What a controller under test reported: (virtual ms, lock) pairs and every status. */
internal class SyncRecorder {
    val locks = mutableListOf<Pair<Long, SyncLock>>()
    val statuses = mutableListOf<AutoSyncStatus>()

    val offsets: List<Long> get() = locks.map { it.second.offsetMs }
}

internal fun TestScope.recordingAutoSync(source: AutoSyncSource, recorder: SyncRecorder) = SubtitleAutoSync(
    backgroundScope,
    source,
    onDetected = { recorder.locks += testScheduler.currentTime to it },
    onStatus = { recorder.statuses += it },
    analysis = StandardTestDispatcher(testScheduler),
)
