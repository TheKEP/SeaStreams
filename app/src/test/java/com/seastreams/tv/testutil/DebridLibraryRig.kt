package com.seastreams.tv.testutil

import com.seastreams.tv.core.datastore.DebridSettingsStore
import com.seastreams.tv.data.debrid.DebridServiceKind
import com.seastreams.tv.data.debrid.DebridSourcesRepository
import com.seastreams.tv.data.debrid.api.DebridAccount
import com.seastreams.tv.data.debrid.api.DebridClients
import com.seastreams.tv.data.debrid.api.DebridFile
import com.seastreams.tv.data.debrid.api.DebridFileHandle
import com.seastreams.tv.data.debrid.api.DebridItem
import com.seastreams.tv.data.debrid.api.DebridLibraryClient
import com.seastreams.tv.data.debrid.api.DebridNotFoundException
import com.seastreams.tv.data.debrid.auth.DebridTokenRefresher
import com.seastreams.tv.data.debrid.auth.TokenRefreshers
import com.seastreams.tv.data.debrid.library.DebridLibraryCache
import com.seastreams.tv.data.debrid.library.DebridLibrarySearch
import com.seastreams.tv.data.debrid.stream.DebridLinkResolver
import com.seastreams.tv.data.debrid.stream.DebridStreamRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import java.time.Clock

/**
 * A scripted [DebridLibraryClient]: lists [items], answers [files] per item id and [links] per file id (a missing link
 * is a deleted file). [libraryDelayMillis] / [libraryError] / [resolveError] script slow or failing services.
 */
class FakeLibraryClient(
    override val kind: DebridServiceKind,
    var items: List<DebridItem> = emptyList(),
    val files: Map<String, List<DebridFile>> = emptyMap(),
    val links: MutableMap<String, String> = mutableMapOf(),
) : DebridLibraryClient {
    var libraryCalls = 0
    var fileCalls = 0
    var resolveCalls = 0
    var libraryDelayMillis = 0L
    var libraryError: Exception? = null
    var resolveError: Exception? = null
    val credentialsSeen = mutableListOf<String>()

    override suspend fun account(credential: String) = DebridAccount(premium = true, premiumUntilMillis = null)

    override suspend fun library(credential: String): List<DebridItem> {
        libraryCalls++
        credentialsSeen += credential
        delay(libraryDelayMillis)
        libraryError?.let { throw it }
        return items
    }

    override suspend fun files(credential: String, item: DebridItem): List<DebridFile> = item.files ?: run {
        fileCalls++
        files[item.id].orEmpty()
    }

    override suspend fun resolve(credential: String, file: DebridFileHandle): String {
        resolveCalls++
        resolveError?.let { throw it }
        return links[file.fileId] ?: throw DebridNotFoundException()
    }
}

/** Real repository, refresher, cache, registry, search and resolver around [FakeLibraryClient]s. */
class DebridLibraryRig(clock: Clock) {
    val dao = FakeDebridProviderDao()
    val settings = DebridSettingsStore(InMemoryPreferences())
    val repository = DebridSourcesRepository(dao, FakeSecretCipher(), Dispatchers.Unconfined)
    private val clientMap = HashMap<DebridServiceKind, DebridLibraryClient>()
    val clients = DebridClients(clientMap)
    val refresher = DebridTokenRefresher(repository, TokenRefreshers { null }, clock)
    val cache = DebridLibraryCache(clock)
    val registry = DebridStreamRegistry()
    val search = DebridLibrarySearch(repository, clients, refresher, cache, registry, settings)
    val resolver = DebridLinkResolver(registry, repository, clients, refresher, cache, settings, clock)

    /** Adds [client]'s account (enabled, at the bottom of the list) and returns its provider id. */
    suspend fun add(client: DebridLibraryClient): Long {
        clientMap[client.kind] = client
        return repository.saveKey(null, client.kind, "key-${client.kind.name.lowercase()}-0123456789", null)
    }

    suspend fun enable() = settings.setEnabled(true)
}

/** A one-file library item (as Real-Debrid downloads and Premiumize files are listed). */
fun debridItem(id: String, name: String, size: Long = 4L * 1024 * 1024 * 1024) =
    DebridItem(id, name, size, listOf(DebridFile("$id-file", name, size)))
