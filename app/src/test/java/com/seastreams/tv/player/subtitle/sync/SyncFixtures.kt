package com.seastreams.tv.player.subtitle.sync

import com.seastreams.tv.player.subtitle.TimedCue
import kotlin.random.Random

/** Synthetic dialogue: cues of 1–4 s separated by 0.5–6 s pauses, deterministic per [seed]. */
internal fun dialogue(seed: Int, fromUs: Long = 0, toUs: Long = 600_000_000): List<TimedCue> {
    val random = Random(seed)
    val cues = ArrayList<TimedCue>()
    var t = fromUs + random.nextLong(0, 3_000_000)
    while (t < toUs) {
        val duration = random.nextLong(1_000_000, 4_000_000)
        cues.add(TimedCue(t, t + duration, "line ${cues.size}"))
        t += duration + random.nextLong(500_000, 6_000_000)
    }
    return cues
}

/** Conversation-paced dialogue: lines of 1.2–3 s with 0.4–2 s pauses (about one line every 2.8 s). */
internal fun conversation(seed: Int, fromUs: Long = 0, toUs: Long = 600_000_000): List<TimedCue> {
    val random = Random(seed)
    val cues = ArrayList<TimedCue>()
    var t = fromUs + random.nextLong(0, 2_000_000)
    while (t < toUs) {
        val duration = random.nextLong(1_200_000, 3_000_000)
        cues.add(TimedCue(t, t + duration, "line ${cues.size}"))
        t += duration + random.nextLong(400_000, 2_000_000)
    }
    return cues
}

/**
 * The same dialogue as another subtitle author would time it: each line ends 0.3–1.2 s earlier or
 * later, and every fourth line is merged with the next one (starts are kept: both follow speech).
 */
internal fun retimed(cues: List<TimedCue>, seed: Int): List<TimedCue> {
    val random = Random(seed)
    val out = ArrayList<TimedCue>()
    var i = 0
    while (i < cues.size) {
        val cue = cues[i]
        val merged = i % 4 == 3 && i + 1 < cues.size
        val end = if (merged) cues[i + 1].endUs else cue.endUs
        val change = random.nextLong(300_000, 1_200_000) * (if (random.nextBoolean()) 1 else -1)
        out.add(TimedCue(cue.startUs, maxOf(cue.startUs + 400_000, end + change), cue.text))
        i += if (merged) 2 else 1
    }
    return out
}

/** [cues] as the external file would carry them when it runs [offsetMs] late (`+` = later). */
internal fun shifted(cues: List<TimedCue>, offsetMs: Long): List<TimedCue> =
    cues.map { it.copy(startUs = it.startUs + offsetMs * 1_000, endUs = it.endUs + offsetMs * 1_000) }

/** Reference grid over `[startCell, startCell + size)` and the matching ±30 s subtitle grid. */
internal fun grids(reference: List<TimedCue>, external: List<TimedCue>, startCell: Long, size: Int): Pair<ActivityGrid, ActivityGrid> {
    val lag = OffsetCorrelator.MAX_LAG_CELLS
    return ActivityGrid.fromCues(reference, startCell, size) to ActivityGrid.fromCues(external, startCell - lag, size + 2 * lag)
}

/** [grid] with a [fraction] of its cells flipped at random (imperfect voice-activity detection). */
internal fun noisy(grid: ActivityGrid, fraction: Double, seed: Int): ActivityGrid {
    val random = Random(seed)
    val active = BooleanArray(grid.size) { if (random.nextDouble() < fraction) !grid.active[it] else grid.active[it] }
    return ActivityGrid(grid.startCell, active, grid.covered.copyOf())
}
