package com.seastreams.tv.usenet.nntp

import com.seastreams.tv.domain.usenet.UsenetPerformance
import com.seastreams.tv.core.util.sha1Hex

/**
 * One enabled provider with its decrypted password, as the engine uses it. Never persisted or logged:
 * [toString] leaves the password out.
 */
data class NntpEndpoint(
    val id: Long,
    val name: String,
    val host: String,
    val port: Int,
    val tls: Boolean,
    val username: String,
    val password: String,
    val maxConnections: Int,
    val pipelineDepth: Int,
    val priority: Int,
    val isBackup: Boolean,
) {
    init {
        require(host.isNotBlank() && host.none { it <= ' ' }) { "Invalid host" }
        require(username.none { it == '\r' || it == '\n' } && password.none { it == '\r' || it == '\n' }) { "Invalid login" }
        require(maxConnections >= 1 && pipelineDepth >= 1) { "Invalid limits" }
    }

    /**
     * Identity of the account + connection shape. [NntpEngine] keys its pools by it, so editing a provider's
     * login, server or limits replaces its pool, while reordering or renaming keeps the warm one.
     */
    val fingerprint: String by lazy {
        sha1Hex("$host\u0000$port\u0000$tls\u0000$username\u0000$password\u0000$maxConnections\u0000$pipelineDepth")
    }

    override fun toString(): String = "NntpEndpoint($name, $host:$port, tls=$tls, max=$maxConnections, depth=$pipelineDepth)"
}

/** Engine tunables, derived from the app-wide [UsenetPerformance] (PRD §12) by [engineSettings]. */
data class NntpEngineSettings(
    val dialTimeoutMs: Int = 15_000,
    /** Socket read timeout: a connection silent this long is dropped and its requests re-queued. */
    val stallTimeoutMs: Int = 30_000,
    /** Longest a reader waits for one segment; `0` = no limit. */
    val segmentTimeoutMs: Long = 30_000,
    val idleTtlMs: Long = 60_000,
    val keepAliveMs: Long = 30_000,
    val breakerThreshold: Int = 5,
    val breakerCooldownMs: Long = 30_000,
    /** Share of contended grants given to playback (HIGH) over background (LOW) work. */
    val streamingShare: Double = 0.8,
    /** Global download permits; `0` = Σ connections × depth of the configured providers. */
    val maxConcurrentDownloads: Int = 0,
    val warmConnections: Int = 2,
    val verifyChecksums: Boolean = true,
    val readAheadSegments: Int = 32,
    val diskCacheBytes: Long = 1_024L * MB,
    /** Pause before re-dialling after a failed dial (grows per consecutive failure). */
    val dialBackoffMs: Long = 500,
)

internal const val MB = 1024L * 1024

fun UsenetPerformance.engineSettings(): NntpEngineSettings = clamped().let { p ->
    NntpEngineSettings(
        dialTimeoutMs = p.dialTimeoutSeconds * 1_000,
        stallTimeoutMs = p.segmentStallTimeoutSeconds * 1_000,
        segmentTimeoutMs = p.segmentTimeoutSeconds * 1_000L,
        idleTtlMs = p.idleConnectionTtlSeconds * 1_000L,
        breakerThreshold = p.circuitBreakerThreshold,
        breakerCooldownMs = p.circuitBreakerCooldownSeconds * 1_000L,
        streamingShare = p.streamingPriorityPercent / 100.0,
        maxConcurrentDownloads = p.maxConcurrentDownloads,
        warmConnections = p.warmConnections,
        verifyChecksums = p.verifyArticleChecksums,
        readAheadSegments = p.readAheadSegments,
        diskCacheBytes = p.segmentDiskCacheMb * MB,
    )
}

/** Monotonic milliseconds; every timed engine class takes a clock like this so tests can run on virtual time. */
val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 }
