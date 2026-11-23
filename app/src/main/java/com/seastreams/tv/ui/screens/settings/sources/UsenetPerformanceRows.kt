package com.seastreams.tv.ui.screens.settings.sources

import com.seastreams.tv.domain.usenet.DamagePolicy
import com.seastreams.tv.domain.usenet.UsenetPerformance
import com.seastreams.tv.domain.usenet.UsenetProfile
import com.seastreams.tv.domain.usenet.VerifyMode
import com.seastreams.tv.ui.screens.settings.PanelItem
import com.seastreams.tv.ui.screens.settings.RowTrailing

/**
 * The rows of `Usenet performance` (PRD §12), grouped under the `aio-usenet-settings1–6.png` sections.
 * [setProfile] applies a template; [edit] applies a single-value change (which switches to Custom).
 */
fun performanceItems(
    p: UsenetPerformance,
    setProfile: (UsenetProfile) -> Unit,
    edit: ((UsenetPerformance) -> UsenetPerformance) -> Unit,
): List<PanelItem> {
    fun number(key: String, title: String, options: List<Int>, value: Int, label: (Int) -> String, set: UsenetPerformance.(Int) -> UsenetPerformance) =
        PanelItem.Row(
            key, title, label(value),
            RowTrailing.Stepper(
                onDecrease = { edit { it.set(stepOption(options, value, -1)) } },
                onIncrease = { edit { it.set(stepOption(options, value, 1)) } },
            ),
        ) { edit { it.set(stepOption(options, value, 1, wrap = true)) } }

    fun toggle(key: String, title: String, checked: Boolean, set: UsenetPerformance.(Boolean) -> UsenetPerformance) =
        PanelItem.Row(key, title, if (checked) "On" else "Off", RowTrailing.Toggle(checked)) { edit { it.set(!checked) } }

    fun <E> choice(key: String, title: String, options: List<E>, value: E, label: (E) -> String, apply: (E) -> Unit) =
        PanelItem.Row(
            key, title, label(value),
            RowTrailing.Stepper(
                onDecrease = { apply(stepChoice(options, value, -1)) },
                onIncrease = { apply(stepChoice(options, value, 1)) },
            ),
        ) { apply(stepChoice(options, value, 1, wrap = true)) }

    return listOf(
        PanelItem.Section("s-performance", "Performance"),
        choice(PROFILE_KEY, "Performance profile", UsenetProfile.entries, p.profile, { it.label }, setProfile),
        number("read-ahead", "Read-ahead (segments)", READ_AHEAD, p.readAheadSegments, Int::toString) { copy(readAheadSegments = it) },
        number("max-downloads", "Max concurrent downloads", MAX_DOWNLOADS, p.maxConcurrentDownloads, { if (it == 0) "Auto" else "$it" }) { copy(maxConcurrentDownloads = it) },
        number("disk-cache", "Segment disk cache size", DISK_CACHE_MB, p.segmentDiskCacheMb, ::megabytesLabel) { copy(segmentDiskCacheMb = it) },
        number("warm", "Warm connections", UsenetPerformance.WARM_CONNECTION_CHOICES, p.warmConnections, Int::toString) { copy(warmConnections = it) },
        PanelItem.Section("s-connections", "Connections & timeouts"),
        number("priority", "Streaming priority share", PRIORITY_PERCENT, p.streamingPriorityPercent, ::shareLabel) { copy(streamingPriorityPercent = it) },
        number("segment-timeout", "Segment timeout", SEGMENT_TIMEOUT, p.segmentTimeoutSeconds, { durationLabel(it, zero = "Never") }) { copy(segmentTimeoutSeconds = it) },
        number("stall-timeout", "Segment stall timeout", STALL_TIMEOUT, p.segmentStallTimeoutSeconds, ::durationLabel) { copy(segmentStallTimeoutSeconds = it) },
        number("dial-timeout", "Dial timeout", DIAL_TIMEOUT, p.dialTimeoutSeconds, ::durationLabel) { copy(dialTimeoutSeconds = it) },
        number("idle-ttl", "Idle connection TTL", IDLE_TTL, p.idleConnectionTtlSeconds, ::durationLabel) { copy(idleConnectionTtlSeconds = it) },
        number("stream-idle", "Stream idle timeout", STREAM_IDLE, p.streamIdleTimeoutSeconds, { durationLabel(it, zero = "Off") }) { copy(streamIdleTimeoutSeconds = it) },
        PanelItem.Section("s-reliability", "Reliability"),
        number("breaker-threshold", "Circuit breaker threshold", BREAKER_THRESHOLD, p.circuitBreakerThreshold, Int::toString) { copy(circuitBreakerThreshold = it) },
        number("breaker-cooldown", "Circuit breaker cooldown", BREAKER_COOLDOWN, p.circuitBreakerCooldownSeconds, ::durationLabel) { copy(circuitBreakerCooldownSeconds = it) },
        PanelItem.Section("s-archives", "Archive handling"),
        toggle("lazy-rar", "Lazy RAR resolution", p.lazyRarResolution) { copy(lazyRarResolution = it) },
        toggle("strict-archive", "Strict archive membership", p.strictArchiveMembership) { copy(strictArchiveMembership = it) },
        PanelItem.Section("s-verification", "Verification"),
        choice("verify-mode", "Verify mode", VerifyMode.entries, p.verifyMode, { it.label }) { mode -> edit { it.copy(verifyMode = mode) } },
        number("verify-budget", "Verify budget", VERIFY_BUDGET, p.verifyBudgetMillis, { "$it ms" }) { copy(verifyBudgetMillis = it) },
        toggle("verify-contents", "Verify file contents", p.verifyFileContents) { copy(verifyFileContents = it) },
        toggle("verify-crc", "Verify article checksums", p.verifyArticleChecksums) { copy(verifyArticleChecksums = it) },
        choice("damage-policy", "Damage policy", DamagePolicy.entries, p.damagePolicy, { it.label }) { policy -> edit { it.copy(damagePolicy = policy) } },
        toggle("mkv-repair", "Matroska hole repair", p.matroskaHoleRepair) { copy(matroskaHoleRepair = it) },
        number("census-concurrency", "Census background concurrency", CENSUS_CONCURRENCY, p.censusConcurrency, Int::toString) { copy(censusConcurrency = it) },
        number("census-lifetime", "Census max lifetime", CENSUS_LIFETIME, p.censusMaxLifetimeMinutes, { durationLabel(it * 60) }) { copy(censusMaxLifetimeMinutes = it) },
        PanelItem.Section("s-import", "Import"),
        number("max-nzb", "Max NZB size", MAX_NZB_MB, p.maxNzbSizeMb, ::megabytesLabel) { copy(maxNzbSizeMb = it) },
        number("max-imports", "Max concurrent imports", MAX_IMPORTS, p.maxConcurrentImports, { if (it == 0) "Unlimited" else "$it" }) { copy(maxConcurrentImports = it) },
    )
}

/**
 * The next option after [current] in [direction] (`+1` / `-1`). Off-list values (typed in the portal)
 * step to their nearest neighbour. At either end a value stays put, except a forward step with [wrap]
 * (OK), which wraps to the first option.
 */
fun stepOption(options: List<Int>, current: Int, direction: Int, wrap: Boolean = false): Int = if (direction > 0) {
    options.firstOrNull { it > current } ?: if (wrap) options.first() else current
} else {
    options.lastOrNull { it < current } ?: current
}

/** Like [stepOption] for a fixed list of choices (enum entries). */
fun <E> stepChoice(options: List<E>, current: E, direction: Int, wrap: Boolean = false): E {
    val index = options.indexOf(current).coerceAtLeast(0)
    val next = index + direction
    return when {
        next in options.indices -> options[next]
        wrap && direction > 0 -> options.first()
        else -> options[index]
    }
}

/** `30s`, `1m`, `1m 30s`, `2h`; [zero] for `0`. */
fun durationLabel(seconds: Int, zero: String = "0s"): String = when {
    seconds <= 0 -> zero
    seconds < 60 -> "${seconds}s"
    seconds < 3_600 -> if (seconds % 60 == 0) "${seconds / 60}m" else "${seconds / 60}m ${seconds % 60}s"
    else -> if (seconds % 3_600 == 0) "${seconds / 3_600}h" else "${seconds / 3_600}h ${(seconds % 3_600) / 60}m"
}

/** `Off`, `512 MB`, `1 GB`, `1.5 GB`. */
fun megabytesLabel(mb: Int): String = when {
    mb <= 0 -> "Off"
    mb < 1_024 -> "$mb MB"
    mb % 1_024 == 0 -> "${mb / 1_024} GB"
    else -> "%.1f GB".format(java.util.Locale.US, mb / 1_024.0)
}

/** `0.80`, the share as AIOStreams writes it. */
fun shareLabel(percent: Int): String = "%d.%02d".format(java.util.Locale.US, percent / 100, percent % 100)

const val PROFILE_KEY = "profile"
private val READ_AHEAD = listOf(1, 4, 8, 16, 24, 32, 48, 64, 96, 128, 192, 256)
private val MAX_DOWNLOADS = listOf(0, 10, 20, 30, 40, 60, 80, 100, 150, 200, 300, 400)
private val DISK_CACHE_MB = listOf(0, 256, 512, 1_024, 2_048, 3_072, 4_096)
private val PRIORITY_PERCENT = (50..100 step 5).toList()
private val SEGMENT_TIMEOUT = listOf(0, 5, 10, 15, 20, 30, 45, 60, 90, 120)
private val STALL_TIMEOUT = listOf(5, 10, 15, 20, 30, 45, 60)
private val DIAL_TIMEOUT = listOf(5, 10, 15, 20, 30, 45, 60)
private val IDLE_TTL = listOf(15, 30, 60, 120, 300, 600)
private val STREAM_IDLE = listOf(0, 300, 900, 1_800, 3_600, 7_200, 14_400)
private val BREAKER_THRESHOLD = (1..20).toList()
private val BREAKER_COOLDOWN = listOf(10, 15, 30, 60, 120, 300)
private val VERIFY_BUDGET = listOf(0, 250, 500, 1_000, 2_000, 5_000)
private val CENSUS_CONCURRENCY = listOf(1, 2, 4, 6, 8, 12, 16, 24, 32)
private val CENSUS_LIFETIME = listOf(5, 10, 15, 30, 60, 120)
private val MAX_NZB_MB = listOf(25, 50, 100, 150, 200, 300, 500)
private val MAX_IMPORTS = (0..8).toList()
