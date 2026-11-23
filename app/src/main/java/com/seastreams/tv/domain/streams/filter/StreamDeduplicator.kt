package com.seastreams.tv.domain.streams.filter

import com.seastreams.tv.domain.streams.DedupKey
import com.seastreams.tv.domain.streams.DedupMode
import com.seastreams.tv.domain.streams.DedupSettings
import com.seastreams.tv.domain.streams.ResultLimits
import com.seastreams.tv.domain.streams.parse.ReleaseRegexTables.UNKNOWN
import java.util.Locale
import kotlin.math.ln
import kotlin.math.roundToLong

/**
 * Removes duplicate streams (AIOStreams `deduplicator.ts`). Two streams are duplicates when they share any enabled
 * key: the normalised file name, the exact URL, or the smart-detect fingerprint (resolution, quality, encode, tags and
 * a size rounded to ~10%). [DedupMode.PER_ADDON] only compares streams of the same addon; [DedupMode.SINGLE_RESULT]
 * keeps one per release across every addon. The first stream (addon order) wins, so the list keeps its order, and
 * duplicates chain: a stream sharing a key with an already dropped duplicate is dropped too.
 */
object StreamDeduplicator {

    fun deduplicate(candidates: List<StreamCandidate>, settings: DedupSettings): List<StreamCandidate> {
        if (!settings.enabled || settings.keys.isEmpty()) return candidates
        val seen = HashSet<String>()
        return candidates.filter { candidate ->
            val scope = if (settings.mode == DedupMode.PER_ADDON) candidate.source.addonUrl + "|" else ""
            val keys = keysOf(candidate, settings.keys).map { scope + it }
            val duplicate = keys.any { it in seen }
            // A dropped duplicate's keys still count, so a chain (same name, then same URL) collapses to one stream.
            seen += keys
            !duplicate
        }
    }

    internal fun keysOf(candidate: StreamCandidate, keys: List<DedupKey>): List<String> = buildList {
        val parsed = candidate.parsed
        if (DedupKey.FILENAME in keys) {
            parsed.filename?.let(::normaliseFilename)?.takeIf { it.isNotEmpty() }?.let { add("filename:$it") }
        }
        if (DedupKey.URL in keys) add("url:" + candidate.source.url)
        if (DedupKey.SMART in keys) {
            val size = parsed.sizeBytes?.takeIf { it > 0 } ?: return@buildList
            val release = parsed.release
            val fingerprint = listOf(
                release.resolution, release.quality, release.encode,
                release.visualTags.sorted().joinToString(","), release.audioTags.sorted().joinToString(","),
                release.audioChannels.sorted().joinToString(","), parsed.languages.sorted().joinToString(","),
                (ln(size.toDouble()) / ln(SIZE_STEP)).roundToLong().toString(),
            ).joinToString("|")
            add("smart:$fingerprint")
        }
    }

    /** Lower case, no extension, letters and digits only: `Movie.2023.1080p.mkv` ≡ `movie 2023 1080p`. */
    internal fun normaliseFilename(filename: String): String =
        filename.lowercase(Locale.ROOT).replace(EXTENSION, "").replace(NON_ALNUM, "")

    /** Sizes within ~10% of each other share a bucket. */
    private const val SIZE_STEP = 1.1
    private val EXTENSION = Regex("\\.[a-z0-9]{2,4}$")
    private val NON_ALNUM = Regex("[^a-z0-9]")
}

/**
 * Result limits (AIOStreams `limiter.ts`), applied after sorting so the best streams survive: walking the list in
 * order, a stream is dropped once the global cap or its resolution's, quality's or addon's cap is reached.
 */
object ResultLimiter {

    fun limit(candidates: List<StreamCandidate>, limits: ResultLimits): List<StreamCandidate> {
        if (limits == ResultLimits()) return candidates
        var total = 0
        val byResolution = HashMap<String, Int>()
        val byQuality = HashMap<String, Int>()
        val byAddon = HashMap<String, Int>()
        return candidates.filter { candidate ->
            val resolution = candidate.parsed.release.resolution ?: UNKNOWN
            val quality = candidate.parsed.release.quality ?: UNKNOWN
            val addon = candidate.source.addonUrl
            val full = reached(total, limits.global) ||
                reached(byResolution[resolution] ?: 0, limits.perResolution) ||
                reached(byQuality[quality] ?: 0, limits.perQuality) ||
                reached(byAddon[addon] ?: 0, limits.perAddon)
            if (!full) {
                total++
                byResolution.merge(resolution, 1, Int::plus)
                byQuality.merge(quality, 1, Int::plus)
                byAddon.merge(addon, 1, Int::plus)
            }
            !full
        }
    }

    private fun reached(count: Int, cap: Int?): Boolean = cap != null && cap > 0 && count >= cap
}
