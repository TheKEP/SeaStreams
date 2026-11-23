package com.seastreams.tv.data.debrid.stream

import com.seastreams.tv.core.datastore.DebridSettingsStore
import com.seastreams.tv.data.debrid.DebridSourcesRepository
import com.seastreams.tv.data.debrid.api.DebridClients
import com.seastreams.tv.data.debrid.api.DebridException
import com.seastreams.tv.data.debrid.api.DebridNotFoundException
import com.seastreams.tv.data.debrid.auth.DebridTokenRefresher
import com.seastreams.tv.data.debrid.library.DebridLibraryCache
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/** `Enable Debrid` is off: Debrid streams (a LAST USED or overlay pick) are skipped. */
class DebridSwitchedOffException : DebridException("Debrid is switched off in Settings")

/**
 * Turns a `seastreams-debrid://<token>` stream into a fresh HTTPS playback link (PRD §14 *Playback*): the service's
 * client, called through [DebridTokenRefresher] (so a Real-Debrid sign-in is renewed first), resolves the file just
 * before playback. Links are reused for [LINK_TTL_MILLIS] per token and per credential generation; [clear] drops them
 * (`Clear Debrid settings`). A file the service says is gone also drops that provider's cached library, so the next
 * search no longer lists it.
 */
@Singleton
class DebridLinkResolver @Inject constructor(
    private val registry: DebridStreamRegistry,
    private val repository: DebridSourcesRepository,
    private val clients: DebridClients,
    private val refresher: DebridTokenRefresher,
    private val library: DebridLibraryCache,
    private val settings: DebridSettingsStore,
    private val clock: Clock,
) {
    private class Link(val url: String, val generation: Long, val resolvedAt: Long)

    private val links = object : LinkedHashMap<String, Link>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Link>?): Boolean = size > MAX_LINKS
    }

    /**
     * The playback link of [token]. Throws a [DebridException] (switched off, account gone or disabled, file deleted,
     * key revoked, not a secure link) or an [IOException] (service unreachable).
     */
    suspend fun resolve(token: String): String {
        val ref = registry.get(token) ?: throw DebridNotFoundException("That Debrid file isn't listed any more")
        if (!settings.enabled.first()) throw DebridSwitchedOffException()
        val generation = repository.generation(ref.kind)
        cached(token, generation)?.let { return it }
        val account = repository.enabledCredentials().firstOrNull { it.providerId == ref.providerId && it.kind == ref.kind }
            ?: throw DebridNotFoundException("That ${ref.kind.displayName} account isn't enabled")
        val url = try {
            refresher.withCredentials(account.providerId) { credentials -> clients.of(ref.kind).resolve(credentials.accessToken, ref.handle) }
        } catch (e: DebridNotFoundException) {
            library.invalidate(ref.providerId)
            throw e
        }
        synchronized(links) { links[token] = Link(url, generation, clock.millis()) }
        return url
    }

    /** [resolve] for a player's loader thread: every refusal is an [IOException] there. */
    fun resolveBlocking(token: String): String = runBlocking {
        try {
            resolve(token)
        } catch (e: DebridException) {
            throw IOException(e.message, e)
        }
    }

    /** `Clear Debrid settings`: every cached link is forgotten. */
    fun clear() = synchronized(links) { links.clear() }

    private fun cached(token: String, generation: Long): String? = synchronized(links) {
        val link = links[token] ?: return null
        if (link.generation == generation && clock.millis() - link.resolvedAt < LINK_TTL_MILLIS) link.url else null.also { links.remove(token) }
    }

    companion object {
        const val LINK_TTL_MILLIS = 20L * 60 * 1_000
        private const val MAX_LINKS = 256
    }
}
