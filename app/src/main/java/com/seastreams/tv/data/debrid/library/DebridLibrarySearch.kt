package com.seastreams.tv.data.debrid.library

import com.seastreams.tv.core.datastore.DebridSettingsStore
import com.seastreams.tv.data.debrid.DebridCredentials
import com.seastreams.tv.data.debrid.DebridSourcesRepository
import com.seastreams.tv.data.debrid.api.DebridClients
import com.seastreams.tv.data.debrid.api.DebridLibraryClient
import com.seastreams.tv.data.debrid.auth.DebridTokenRefresher
import com.seastreams.tv.data.debrid.stream.DebridFileRef
import com.seastreams.tv.data.debrid.stream.DebridStreamRegistry
import com.seastreams.tv.data.usenet.newznab.UsenetStreamSearch
import com.seastreams.tv.domain.model.StreamHints
import com.seastreams.tv.domain.model.StreamSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** The built-in `Debrid` addon's answer: [failed] when every provider asked failed or timed out. */
data class DebridSearchResult(val streams: List<StreamSource>, val failed: Boolean)

/**
 * The built-in `Debrid` stream search (PRD §14): every enabled provider's library ([DebridLibraryCache], 60 s) is
 * matched against the title ([DebridLibraryMatcher]) in parallel. A provider that fails or takes longer than
 * [TIMEOUT_MILLIS] only loses its own results; results are listed in provider order, each provider's strongest matches
 * first. At most [MAX_FILE_LISTS] item file lists are read per provider per lookup. Library-only: nothing is ever
 * sent to a service but its own listing, file-list and account calls.
 */
@Singleton
class DebridLibrarySearch @Inject constructor(
    private val repository: DebridSourcesRepository,
    private val clients: DebridClients,
    private val refresher: DebridTokenRefresher,
    private val cache: DebridLibraryCache,
    private val registry: DebridStreamRegistry,
    private val settings: DebridSettingsStore,
) {
    /** Whether the `Debrid` card is shown: `Enable Debrid` is ON and an enabled provider has a usable credential. */
    suspend fun configured(): Boolean = settings.enabled.first() && repository.enabledCredentials().isNotEmpty()

    suspend fun search(target: DebridSearchTarget): DebridSearchResult = coroutineScope {
        val accounts = repository.enabledCredentials()
        cache.retain(accounts.mapTo(HashSet()) { it.providerId })
        val answers = accounts.map { account ->
            async {
                try {
                    withTimeoutOrNull(TIMEOUT_MILLIS) { lookup(account, target) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null // this provider's failure drops only its own results
                }
            }
        }.awaitAll()
        DebridSearchResult(answers.flatMap { it.orEmpty() }, failed = answers.isNotEmpty() && answers.all { it == null })
    }

    private suspend fun lookup(account: DebridCredentials, target: DebridSearchTarget): List<StreamSource> {
        val client = clients.of(account.kind)
        val generation = repository.generation(account.kind)
        val items = cache.library(account.providerId, generation) {
            refresher.withCredentials(account.providerId) { client.library(it.accessToken) }
        }
        var fileLists = 0
        val seen = HashSet<String>()
        return DebridLibraryMatcher.rank(items, target).flatMap { ranked ->
            val files = ranked.item.files ?: if (fileLists < MAX_FILE_LISTS) {
                fileLists++
                files(account, client, generation, ranked)
            } else {
                return@flatMap emptyList()
            }
            DebridLibraryMatcher.pick(ranked, files, target).mapNotNull { file ->
                val ref = DebridFileRef(account.providerId, account.kind, ranked.item.id, file.id, file.link, file.name)
                val url = registry.register(ref)
                if (!seen.add(url)) return@mapNotNull null
                toStream(account, url, file.name, file.sizeBytes)
            }
        }
    }

    private suspend fun files(account: DebridCredentials, client: DebridLibraryClient, generation: Long, ranked: RankedItem) =
        cache.files(account.providerId, generation, ranked.item.id) {
            refresher.withCredentials(account.providerId) { client.files(it.accessToken, ranked.item) }
        }

    private fun toStream(account: DebridCredentials, url: String, name: String, size: Long): StreamSource {
        val service = account.kind.displayName
        return StreamSource(
            addonUrl = DEBRID_ADDON_URL,
            addonName = DEBRID_ADDON_NAME,
            url = url,
            label = listOfNotNull(service, UsenetStreamSearch.resolution(name)).joinToString(" · "),
            description = name + "\n" + listOfNotNull(UsenetStreamSearch.formatSize(size), service).joinToString(" · "),
            headers = emptyMap(),
            hints = StreamHints(filename = name, sizeBytes = size.takeIf { it > 0 }, indexer = service),
        )
    }

    companion object {
        const val DEBRID_ADDON_URL = "debrid://builtin"
        const val DEBRID_ADDON_NAME = "Debrid"
        /** Per-provider deadline (as Usenet's search); the resolve's own timeout is a little longer. */
        const val TIMEOUT_MILLIS = 20_000L

        /** File lists read per provider per lookup, so large libraries stay within the services' rate limits. */
        const val MAX_FILE_LISTS = 8
    }
}
