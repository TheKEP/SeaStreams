package com.seastreams.tv.domain.usenet

import kotlinx.serialization.Serializable

/** Usenet performance profile (PRD §12). [CUSTOM] keeps whatever values were edited. */
@Serializable
enum class UsenetProfile(val label: String) {
    CONSERVATIVE("Conservative"),
    BALANCED("Balanced"),
    HIGH("High"),
    CUSTOM("Custom"),
}

@Serializable
enum class VerifyMode(val label: String) {
    CENSUS("Census"),
    NONE("None"),
}

@Serializable
enum class DamagePolicy(val label: String) {
    STRICT("Strict"),
    TOLERANT("Tolerant"),
}

/**
 * Every value of the `Usenet performance` sub-panel (PRD §12, `aio-usenet-settings1–6.png`). App-wide.
 * Defaults are the [UsenetProfile.BALANCED] template. A profile overwrites all values ([withProfile]);
 * editing any value switches to [UsenetProfile.CUSTOM] ([edited]). Values are clamped on read and write.
 */
@Serializable
data class UsenetPerformance(
    val profile: UsenetProfile = UsenetProfile.BALANCED,
    // Performance
    val readAheadSegments: Int = 32,
    /** `0` = auto: the sum of every enabled provider's connections × pipeline depth. */
    val maxConcurrentDownloads: Int = 0,
    /** `0` disables the segment disk cache. */
    val segmentDiskCacheMb: Int = 1_024,
    val warmConnections: Int = 2,
    // Connections & timeouts
    /** Share of contended download slots given to playback over background checks, 50–100 %. */
    val streamingPriorityPercent: Int = 80,
    /** `0` = never give up on a piece that is still downloading. */
    val segmentTimeoutSeconds: Int = 30,
    val segmentStallTimeoutSeconds: Int = 30,
    val dialTimeoutSeconds: Int = 15,
    val idleConnectionTtlSeconds: Int = 60,
    /** `0` = never close an idle playback stream. */
    val streamIdleTimeoutSeconds: Int = 3_600,
    // Reliability
    val circuitBreakerThreshold: Int = 5,
    val circuitBreakerCooldownSeconds: Int = 30,
    // Archive handling
    val lazyRarResolution: Boolean = true,
    val strictArchiveMembership: Boolean = false,
    // Verification
    val verifyMode: VerifyMode = VerifyMode.CENSUS,
    val verifyBudgetMillis: Int = 0,
    val verifyFileContents: Boolean = false,
    val verifyArticleChecksums: Boolean = true,
    val damagePolicy: DamagePolicy = DamagePolicy.STRICT,
    val matroskaHoleRepair: Boolean = true,
    val censusConcurrency: Int = 8,
    val censusMaxLifetimeMinutes: Int = 30,
    // Import
    val maxNzbSizeMb: Int = 150,
    /** `0` = unlimited. */
    val maxConcurrentImports: Int = 4,
) {

    /** The [profile] template's values, or these values marked [UsenetProfile.CUSTOM]. */
    fun withProfile(next: UsenetProfile): UsenetPerformance =
        if (next == UsenetProfile.CUSTOM) copy(profile = UsenetProfile.CUSTOM) else template(next)

    /** Applies a single-value edit; any edit switches the profile to [UsenetProfile.CUSTOM]. */
    fun edited(transform: (UsenetPerformance) -> UsenetPerformance): UsenetPerformance =
        transform(this).copy(profile = UsenetProfile.CUSTOM).clamped()

    /** Bounds every value to its PRD §12 range (and warm connections to 0 / 2 / 4). */
    fun clamped(): UsenetPerformance = copy(
        readAheadSegments = readAheadSegments.coerceIn(READ_AHEAD_RANGE),
        maxConcurrentDownloads = maxConcurrentDownloads.coerceIn(MAX_DOWNLOADS_RANGE),
        segmentDiskCacheMb = segmentDiskCacheMb.coerceIn(DISK_CACHE_MB_RANGE),
        warmConnections = WARM_CONNECTION_CHOICES.minBy { kotlin.math.abs(it - warmConnections) },
        streamingPriorityPercent = streamingPriorityPercent.coerceIn(PRIORITY_PERCENT_RANGE),
        segmentTimeoutSeconds = segmentTimeoutSeconds.coerceIn(SEGMENT_TIMEOUT_RANGE),
        segmentStallTimeoutSeconds = segmentStallTimeoutSeconds.coerceIn(STALL_TIMEOUT_RANGE),
        dialTimeoutSeconds = dialTimeoutSeconds.coerceIn(DIAL_TIMEOUT_RANGE),
        idleConnectionTtlSeconds = idleConnectionTtlSeconds.coerceIn(IDLE_TTL_RANGE),
        streamIdleTimeoutSeconds = streamIdleTimeoutSeconds.coerceIn(STREAM_IDLE_RANGE),
        circuitBreakerThreshold = circuitBreakerThreshold.coerceIn(BREAKER_THRESHOLD_RANGE),
        circuitBreakerCooldownSeconds = circuitBreakerCooldownSeconds.coerceIn(BREAKER_COOLDOWN_RANGE),
        verifyBudgetMillis = verifyBudgetMillis.coerceIn(VERIFY_BUDGET_RANGE),
        censusConcurrency = censusConcurrency.coerceIn(CENSUS_CONCURRENCY_RANGE),
        censusMaxLifetimeMinutes = censusMaxLifetimeMinutes.coerceIn(CENSUS_LIFETIME_RANGE),
        maxNzbSizeMb = maxNzbSizeMb.coerceIn(MAX_NZB_MB_RANGE),
        maxConcurrentImports = maxConcurrentImports.coerceIn(MAX_IMPORTS_RANGE),
    )

    companion object {
        val READ_AHEAD_RANGE = 1..256
        val MAX_DOWNLOADS_RANGE = 0..400
        val DISK_CACHE_MB_RANGE = 0..4_096
        val WARM_CONNECTION_CHOICES = listOf(0, 2, 4)
        val PRIORITY_PERCENT_RANGE = 50..100
        val SEGMENT_TIMEOUT_RANGE = 0..120
        val STALL_TIMEOUT_RANGE = 5..60
        val DIAL_TIMEOUT_RANGE = 5..60
        val IDLE_TTL_RANGE = 15..600
        val STREAM_IDLE_RANGE = 0..14_400
        val BREAKER_THRESHOLD_RANGE = 1..20
        val BREAKER_COOLDOWN_RANGE = 10..300
        val VERIFY_BUDGET_RANGE = 0..5_000
        val CENSUS_CONCURRENCY_RANGE = 1..32
        val CENSUS_LIFETIME_RANGE = 5..120
        val MAX_NZB_MB_RANGE = 25..500
        val MAX_IMPORTS_RANGE = 0..8

        /** The PRD §12 template of [profile]; [UsenetProfile.CUSTOM] has none and yields the default (Balanced) values. */
        fun template(profile: UsenetProfile): UsenetPerformance = when (profile) {
            UsenetProfile.CONSERVATIVE -> UsenetPerformance(
                profile = profile,
                readAheadSegments = 16,
                maxConcurrentDownloads = 30,
                segmentDiskCacheMb = 512,
                warmConnections = 0,
                streamingPriorityPercent = 90,
                censusConcurrency = 4,
                maxConcurrentImports = 2,
            )
            UsenetProfile.BALANCED, UsenetProfile.CUSTOM -> UsenetPerformance(profile = profile)
            UsenetProfile.HIGH -> UsenetPerformance(
                profile = profile,
                readAheadSegments = 64,
                segmentDiskCacheMb = 2_048,
                warmConnections = 4,
                segmentTimeoutSeconds = 15,
                segmentStallTimeoutSeconds = 10,
                censusConcurrency = 12,
            )
        }
    }
}
