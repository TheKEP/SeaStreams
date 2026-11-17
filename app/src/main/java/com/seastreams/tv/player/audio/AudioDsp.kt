package com.seastreams.tv.player.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/** `10^(dB/20)`. */
fun dbToLinear(db: Float): Float = 10f.pow(db / DB_PER_DECADE)

fun linearToDb(linear: Float): Float = if (linear <= 0f) MIN_DB else DB_PER_DECADE * log10(linear)

/**
 * Transparent below [SOFT_KNEE], then bends smoothly towards ±1 so boosted peaks never clip harshly.
 */
fun softLimit(sample: Float): Float {
    val magnitude = abs(sample)
    if (magnitude <= SOFT_KNEE) return sample
    val headroom = 1f - SOFT_KNEE
    val shaped = SOFT_KNEE + headroom * tanh((magnitude - SOFT_KNEE) / headroom)
    return if (sample < 0) -shaped else shaped
}

/** RBJ cookbook biquad (direct form I). */
class Biquad private constructor(
    private val b0: Float,
    private val b1: Float,
    private val b2: Float,
    private val a1: Float,
    private val a2: Float,
) {
    private var x1 = 0f
    private var x2 = 0f
    private var y1 = 0f
    private var y2 = 0f

    fun process(x: Float): Float {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = x
        y2 = y1
        y1 = y
        return y
    }

    companion object {
        /** Constant 0 dB peak-gain band-pass centred on [centerHz]. */
        fun bandpass(sampleRate: Int, centerHz: Float, q: Float): Biquad {
            val w0 = 2.0 * PI * centerHz / sampleRate.coerceAtLeast(1)
            val alpha = sin(w0) / (2.0 * q)
            val a0 = 1.0 + alpha
            return Biquad(
                b0 = (alpha / a0).toFloat(),
                b1 = 0f,
                b2 = (-alpha / a0).toFloat(),
                a1 = (-2.0 * cos(w0) / a0).toFloat(),
                a2 = ((1.0 - alpha) / a0).toFloat(),
            )
        }
    }
}

/**
 * Night Mode dynamic range compressor: peak envelope follower (fast attack, slow release) driving a
 * hard-knee gain computer, plus make-up gain so quiet dialogue comes up while loud peaks come down.
 */
class Compressor(
    private val thresholdDb: Float = -30f,
    private val ratio: Float = 4f,
    private val makeupDb: Float = 9f,
    private val attackMs: Float = 5f,
    private val releaseMs: Float = 250f,
) {
    private var attackCoeff = 0f
    private var releaseCoeff = 0f
    private var envelope = 0f
    private val makeup = dbToLinear(makeupDb)

    fun configure(sampleRate: Int) {
        val rate = sampleRate.coerceAtLeast(1)
        attackCoeff = exp(-1.0 / (attackMs / MS_PER_SECOND * rate)).toFloat()
        releaseCoeff = exp(-1.0 / (releaseMs / MS_PER_SECOND * rate)).toFloat()
        envelope = 0f
    }

    /** Linear gain for a frame whose loudest channel is [peak]. */
    fun gainFor(peak: Float): Float {
        val coeff = if (peak > envelope) attackCoeff else releaseCoeff
        envelope = coeff * envelope + (1f - coeff) * peak
        val levelDb = linearToDb(envelope)
        val reductionDb = if (levelDb > thresholdDb) (levelDb - thresholdDb) * (1f - 1f / ratio) else 0f
        return dbToLinear(-reductionDb) * makeup
    }

    private companion object {
        const val MS_PER_SECOND = 1_000f
    }
}

private const val DB_PER_DECADE = 20f
private const val MIN_DB = -120f
private const val SOFT_KNEE = 0.85f
