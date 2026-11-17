package com.seastreams.tv.player

import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.upstream.DefaultAllocator
import com.seastreams.tv.core.datastore.PlaybackBufferProfile

/** `DefaultLoadControl` thresholds for one [PlaybackBufferProfile]. */
data class LoadControlSpec(
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val bufferForPlaybackAfterRebufferMs: Int,
    val targetBufferBytes: Int,
)

private const val MEGABYTE = 1024 * 1024
private const val START_BUFFER_MS = 2_500

/**
 * PRD §9 Playback Buffer Profile. `Large` and `Max` bank minutes of video during fast bursts and wait
 * for 5s / 8s of video after a stall, so short ISP/peering dips don't rebuffer again straight away.
 * The byte target always wins over the time thresholds, keeping high-bitrate 4K remuxes in memory.
 */
fun PlaybackBufferProfile.loadControlSpec(): LoadControlSpec = when (this) {
    PlaybackBufferProfile.STANDARD -> LoadControlSpec(50_000, 50_000, START_BUFFER_MS, 5_000, 64 * MEGABYTE)
    PlaybackBufferProfile.LARGE -> LoadControlSpec(120_000, 150_000, START_BUFFER_MS, 5_000, 150 * MEGABYTE)
    PlaybackBufferProfile.MAX -> LoadControlSpec(300_000, 300_000, START_BUFFER_MS, 8_000, 250 * MEGABYTE)
}

/** A load control plus its allocator, whose allocated bytes are the Info overlay's `buffered MB`. */
class BufferedLoadControl(val loadControl: DefaultLoadControl, val allocator: DefaultAllocator)

fun buildLoadControl(spec: LoadControlSpec): BufferedLoadControl {
    val allocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)
    val loadControl = DefaultLoadControl.Builder()
        .setAllocator(allocator)
        .setBufferDurationsMs(
            spec.minBufferMs,
            spec.maxBufferMs,
            spec.bufferForPlaybackMs,
            spec.bufferForPlaybackAfterRebufferMs,
        )
        .setTargetBufferBytes(spec.targetBufferBytes)
        .setPrioritizeTimeOverSizeThresholds(false)
        .build()
    return BufferedLoadControl(loadControl, allocator)
}
