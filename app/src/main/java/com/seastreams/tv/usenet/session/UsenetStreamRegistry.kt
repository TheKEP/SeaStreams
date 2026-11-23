package com.seastreams.tv.usenet.session

import com.seastreams.tv.core.util.sha1Hex
import com.seastreams.tv.data.usenet.newznab.NzbRequest
import com.seastreams.tv.usenet.nntp.NntpEndpoint
import javax.inject.Inject
import javax.inject.Singleton

/** How to get one release's NZB, and (for addon streams) which servers to read it from. */
sealed interface UsenetGrab {
    val nzb: NzbRequest

    /** The release name shown and logged; never holds a key. */
    val title: String

    /** The stable identity the token is derived from (indexer + guid, or addon + link). */
    val identity: String

    /** A release found by the built-in `Usenet` search. */
    data class Indexed(
        val indexerId: Long,
        val indexerName: String,
        val guid: String,
        override val nzb: NzbRequest,
        override val title: String,
        val sizeBytes: Long,
    ) : UsenetGrab {
        override val identity: String get() = "indexer\u0000$indexerId\u0000$guid"
        override fun toString(): String = "Indexed($indexerName, $title)"
    }

    /** A Stremio addon stream with an `nzbUrl`; [servers] are its own `servers` (empty = the user's providers). */
    data class FromAddon(
        val addonUrl: String,
        override val nzb: NzbRequest,
        override val title: String,
        val servers: List<NntpEndpoint>,
    ) : UsenetGrab {
        override val identity: String get() = "addon\u0000$addonUrl\u0000${nzb.url}"
        override fun toString(): String = "FromAddon($title, servers=${servers.size})"
    }
}

/**
 * Maps the opaque `seastreams-nntp://<token>` stream URLs to their [UsenetGrab] (ARCHITECTURE §10), so indexer API
 * keys and NNTP passwords never reach a URL, an intent, the player or `last_used_streams`. The token is a hash of
 * the release's identity, so the same release found again gets the same URL (LAST USED matches it exactly).
 * In memory only, bounded LRU.
 */
@Singleton
class UsenetStreamRegistry @Inject constructor() {
    private val grabs = object : LinkedHashMap<String, UsenetGrab>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, UsenetGrab>?): Boolean = size > MAX_ENTRIES
    }

    /** Records [grab] and returns its stream URL. */
    @Synchronized
    fun register(grab: UsenetGrab): String {
        val token = tokenFor(grab)
        grabs[token] = grab
        return SCHEME_PREFIX + token
    }

    @Synchronized
    fun get(token: String): UsenetGrab? = grabs[token]

    companion object {
        const val SCHEME = "seastreams-nntp"
        const val SCHEME_PREFIX = "$SCHEME://"
        private const val MAX_ENTRIES = 2_000
        private const val TOKEN_CHARS = 32
        private val TOKEN = Regex("^[0-9a-f]{$TOKEN_CHARS}$")

        fun tokenFor(grab: UsenetGrab): String = sha1Hex(grab.identity).take(TOKEN_CHARS)

        fun isUsenet(url: String): Boolean = url.startsWith(SCHEME_PREFIX)

        /** The token of a `seastreams-nntp://<token>` URL, or `null` for anything else. */
        fun tokenOf(url: String): String? =
            url.takeIf(::isUsenet)?.removePrefix(SCHEME_PREFIX)?.substringBefore('/')?.takeIf { isToken(it) }

        fun isToken(value: String): Boolean = TOKEN.matches(value)
    }
}
