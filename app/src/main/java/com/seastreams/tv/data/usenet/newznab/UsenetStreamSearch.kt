package com.seastreams.tv.data.usenet.newznab

import com.seastreams.tv.data.usenet.UsenetSourcesRepository
import com.seastreams.tv.domain.model.StreamHints
import com.seastreams.tv.domain.model.StreamSource
import com.seastreams.tv.usenet.session.UsenetEngineSetup
import com.seastreams.tv.usenet.session.UsenetGrab
import com.seastreams.tv.usenet.session.UsenetStreamRegistry
import com.seastreams.tv.usenet.verify.ReleaseHealthCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.Clock
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** The built-in `Usenet` addon's answer: [failed] when every indexer asked failed. */
data class UsenetSearchResult(val streams: List<StreamSource>, val failed: Boolean)

/**
 * The built-in `Usenet` stream search (PRD §12): every enabled non-backup indexer is asked in parallel; the
 * *Backup only* ones only when none of those returned a release. Results are ranked by indexer priority tier
 * (lower first), then indexer order, then the indexer's own order; the same release (name + size) found by two
 * indexers is kept once, from the better tier. Passworded releases and ones known broken ([ReleaseHealthCache])
 * are dropped. Search reads only the Newznab XML: it never grabs an NZB (`t=get`), so indexer grab quotas are spent
 * only when a release is played. Each release becomes an opaque `seastreams-nntp://` stream.
 */
@Singleton
class UsenetStreamSearch @Inject constructor(
    private val repository: UsenetSourcesRepository,
    private val client: NewznabClient,
    private val registry: UsenetStreamRegistry,
    private val health: ReleaseHealthCache,
    private val engineSetup: UsenetEngineSetup,
    private val clock: Clock,
) {

    /** Whether the `Usenet` card is shown: at least one usable provider and one usable indexer are enabled. */
    suspend fun configured(): Boolean = repository.enabledEndpoints().isNotEmpty() && repository.enabledIndexers().isNotEmpty()

    suspend fun search(target: UsenetSearchTarget): UsenetSearchResult = coroutineScope {
        launch { runCatching { engineSetup.warmUp() } }
        val indexers = repository.enabledIndexers().withIndex().toList()
        val (backups, primaries) = indexers.partition { it.value.backupOnly }
        var answers = ask(primaries, target)
        if (answers.none { it.hits.isNotEmpty() } && backups.isNotEmpty()) answers = answers + ask(backups, target)
        val ranked = answers.sortedWith(compareBy({ it.backup }, { it.endpoint.priority }, { it.order }))
        val seen = HashSet<String>()
        val streams = ranked.flatMap { answer ->
            answer.hits.mapNotNull { item ->
                if (!seen.add(ReleaseMatch.normalize(item.title) + "|" + item.sizeBytes / SIZE_BUCKET)) return@mapNotNull null
                toStream(answer.endpoint, item)
            }
        }
        UsenetSearchResult(streams, failed = answers.isNotEmpty() && answers.all { it.failed })
    }

    private suspend fun ask(indexers: List<IndexedValue<NewznabEndpoint>>, target: UsenetSearchTarget): List<Answer> = coroutineScope {
        indexers.map { (order, endpoint) -> async { answer(order, endpoint, target) } }.awaitAll()
    }

    private suspend fun answer(order: Int, endpoint: NewznabEndpoint, target: UsenetSearchTarget): Answer = try {
        Answer(endpoint, order, hits(endpoint, target), failed = false)
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        Answer(endpoint, order, emptyList(), failed = true)
    }

    /** The id query, then the `q=` fallback when the id query is unsupported or found nothing. */
    private suspend fun hits(endpoint: NewznabEndpoint, target: UsenetSearchTarget): List<NewznabItem> {
        val caps = client.caps(endpoint)
        for (query in NewznabQueries.plan(caps, target)) {
            val found = client.search(endpoint, query)
                .filter { !it.passworded && ReleaseMatch.matches(it.title, target, strict = !query.byId) }
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    private fun toStream(endpoint: NewznabEndpoint, item: NewznabItem): StreamSource? {
        val grab = UsenetGrab.Indexed(
            indexerId = endpoint.id,
            indexerName = endpoint.name,
            guid = item.guid,
            nzb = NzbRequest(item.nzbUrl, cacheKey = "indexer:${endpoint.id}:${item.guid}"),
            title = item.title,
            sizeBytes = item.sizeBytes,
        )
        if (health.unplayable(UsenetStreamRegistry.tokenFor(grab))) return null
        val url = registry.register(grab)
        val details = listOfNotNull(formatSize(item.sizeBytes), item.publishedMillis?.let(::age), endpoint.name)
        return StreamSource(
            addonUrl = USENET_ADDON_URL,
            addonName = USENET_ADDON_NAME,
            url = url,
            label = listOfNotNull(USENET_ADDON_NAME, resolution(item.title)).joinToString(" · "),
            description = item.title + "\n" + details.joinToString(" · "),
            headers = emptyMap(),
            hints = StreamHints(filename = item.title, sizeBytes = item.sizeBytes.takeIf { it > 0 }, publishedMillis = item.publishedMillis, indexer = endpoint.name),
        )
    }

    private fun age(publishedMillis: Long): String {
        val days = ((clock.millis() - publishedMillis) / DAY_MILLIS).coerceAtLeast(0)
        return if (days == 1L) "1 day old" else "$days days old"
    }

    private class Answer(val endpoint: NewznabEndpoint, val order: Int, val hits: List<NewznabItem>, val failed: Boolean) {
        val backup: Boolean get() = endpoint.backupOnly
    }

    companion object {
        const val USENET_ADDON_URL = "usenet://builtin"
        const val USENET_ADDON_NAME = "Usenet"
        private const val SIZE_BUCKET = 1024L * 1024
        private const val DAY_MILLIS = 24L * 60 * 60 * 1000
        private val RESOLUTION = Regex("(?i)(?<![0-9])(2160p|1080p|720p|576p|480p|4k|uhd)(?![a-z0-9])")

        fun resolution(title: String): String? = RESOLUTION.find(title)?.value?.lowercase(Locale.ROOT)?.let {
            if (it == "4k" || it == "uhd") "2160p" else it
        }

        fun formatSize(bytes: Long): String? = when {
            bytes <= 0 -> null
            bytes >= GB -> String.format(Locale.ROOT, "%.1f GB", bytes / GB.toDouble())
            else -> String.format(Locale.ROOT, "%d MB", bytes / MB)
        }

        private const val MB = 1024L * 1024
        private const val GB = 1024L * MB
    }
}
