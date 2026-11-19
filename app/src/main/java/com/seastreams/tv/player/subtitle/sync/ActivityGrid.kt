package com.seastreams.tv.player.subtitle.sync

import com.seastreams.tv.player.subtitle.TimedCue

/** Auto-sync works on a 100 ms media-time grid: one cell per `0.1s` sync step. */
const val CELL_US = 100_000L

/** One cell index → media time (µs) of its start. */
fun cellStartUs(cell: Long): Long = cell * CELL_US

/** Media time (µs) → the cell holding it. */
fun cellOf(timeUs: Long): Long = Math.floorDiv(timeUs, CELL_US)

/** A media-time span (µs, end exclusive) that was actually observed, e.g. reference cues between seeks. */
data class ObservedSpan(val startUs: Long, val endUs: Long)

/** Per-cell speech measurements for a run of cells, as the audio tap logged them. */
class SpeechSnapshot(
    val startCell: Long,
    /** Voice-band (300 Hz–3.4 kHz) energy in dB per cell. */
    val bandDb: FloatArray,
    /** Voice-band share of the total energy per cell (0..1). */
    val voiceRatio: FloatArray,
    /** Whether audio for the cell was heard at all (seeks leave holes). */
    val covered: BooleanArray,
)

/**
 * Speech / no-speech activity over cells `startCell until startCell + size`. Cells that were never
 * observed are not [covered] and take no part in the correlation.
 */
class ActivityGrid(val startCell: Long, val active: BooleanArray, val covered: BooleanArray) {

    val size: Int get() = active.size

    val coveredCount: Int get() = covered.count { it }

    companion object {

        /**
         * Rasterises [cues] (a cue fills every cell whose centre it covers). With [spans], only cells
         * inside an observed span are covered; without, every cell is.
         */
        fun fromCues(cues: List<TimedCue>, startCell: Long, size: Int, spans: List<ObservedSpan>? = null): ActivityGrid {
            val active = BooleanArray(size)
            cues.forEach { cue -> fill(active, startCell, cue.startUs, cue.endUs, true) }
            val covered = if (spans == null) {
                BooleanArray(size) { true }
            } else {
                BooleanArray(size).also { mask -> spans.forEach { fill(mask, startCell, it.startUs, it.endUs, true) } }
            }
            return ActivityGrid(startCell, active, covered)
        }

        /**
         * A pulse of ±[ONSET_PULSE_CELLS] cells at every cue **start** (coverage as [fromCues]). Two
         * subtitle files of the same film agree on when lines start (speech onsets) far better than
         * on how long they stay up (display durations, line merges, SDH lines differ), so the embedded
         * reference is correlated on starts: duration differences would otherwise drag the peak off the
         * true offset.
         */
        fun fromCueStarts(cues: List<TimedCue>, startCell: Long, size: Int, spans: List<ObservedSpan>? = null): ActivityGrid {
            val pulse = ONSET_PULSE_CELLS * CELL_US
            val pulses = cues.map { TimedCue(it.startUs - pulse, it.startUs + pulse + CELL_US, it.text) }
            return fromCues(pulses, startCell, size, spans)
        }

        /**
         * Adaptive voice-activity decision per cell: voice-band energy at least [FLOOR_MARGIN_DB] above
         * the window's noise floor (its [FLOOR_PERCENTILE] quietest covered cells) with a voice-band
         * share of at least [MIN_VOICE_RATIO], then extended by a [HANGOVER_CELLS] hangover so short
         * pauses between words stay speech.
         */
        fun fromSpeech(snapshot: SpeechSnapshot): ActivityGrid {
            val size = snapshot.covered.size
            val levels = (0 until size).filter { snapshot.covered[it] }.map { snapshot.bandDb[it] }.sorted()
            val active = BooleanArray(size)
            if (levels.isNotEmpty()) {
                val floor = levels[((levels.size - 1) * FLOOR_PERCENTILE).toInt()]
                val threshold = floor + FLOOR_MARGIN_DB
                var hangover = 0
                for (i in 0 until size) {
                    if (!snapshot.covered[i]) {
                        hangover = 0
                        continue
                    }
                    val voiced = snapshot.bandDb[i] >= threshold && snapshot.voiceRatio[i] >= MIN_VOICE_RATIO
                    hangover = if (voiced) HANGOVER_CELLS else hangover - 1
                    active[i] = voiced || hangover > 0
                }
            }
            return ActivityGrid(snapshot.startCell, active, snapshot.covered.copyOf())
        }

        /** Marks the cells whose centre lies in `[startUs, endUs)`. */
        private fun fill(cells: BooleanArray, startCell: Long, startUs: Long, endUs: Long, value: Boolean) {
            if (endUs <= startUs) return
            val half = CELL_US / 2
            val first = ceilDiv(startUs - half, CELL_US) - startCell
            val last = ceilDiv(endUs - half, CELL_US) - startCell
            val from = first.coerceIn(0, cells.size.toLong()).toInt()
            val to = last.coerceIn(0, cells.size.toLong()).toInt()
            for (i in from until to) cells[i] = value
        }

        private fun ceilDiv(value: Long, divisor: Long): Long = -Math.floorDiv(-value, divisor)

        const val FLOOR_PERCENTILE = 0.2
        const val FLOOR_MARGIN_DB = 9f
        const val MIN_VOICE_RATIO = 0.3f
        const val HANGOVER_CELLS = 2

        /** Half-width of a cue-start pulse: ±0.2 s, the usual spread between two authors' timings. */
        const val ONSET_PULSE_CELLS = 2
    }
}
