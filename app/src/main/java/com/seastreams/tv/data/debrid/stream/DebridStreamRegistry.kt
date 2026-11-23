package com.seastreams.tv.data.debrid.stream

import com.seastreams.tv.core.util.sha1Hex
import com.seastreams.tv.data.debrid.DebridServiceKind
import com.seastreams.tv.data.debrid.api.DebridFileHandle
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One library file a Debrid stream plays (ARCHITECTURE §12): which account, which item and file, and the service link
 * it resolves from. Never holds a key, token or playback link; [toString] prints no link.
 */
class DebridFileRef(
    val providerId: Long,
    val kind: DebridServiceKind,
    val itemId: String,
    val fileId: String,
    val restrictedLink: String?,
    val name: String,
) {
    /** The stable library identity the token is derived from. */
    val identity: String get() = kind.name + SEP + itemId + SEP + fileId

    val handle: DebridFileHandle get() = DebridFileHandle(itemId, fileId, restrictedLink)

    override fun toString(): String = "DebridFileRef($kind, $name)"

    private companion object {
        const val SEP = "\u0000"
    }
}

/**
 * Maps the opaque `seastreams-debrid://<token>` stream URLs to their [DebridFileRef] (ARCHITECTURE §12), so no key,
 * token or temporary link ever reaches a URL, an intent or `last_used_streams`. The token is the first 32 hex chars of
 * SHA-1(service + item id + file id): the same file found by a later search gets the same URL, so LAST USED matches it
 * exactly. In memory only, bounded LRU; a token it no longer knows (after a restart) is registered again by the next
 * search of its title, which always runs before playback.
 */
@Singleton
class DebridStreamRegistry @Inject constructor() {
    private val refs = object : LinkedHashMap<String, DebridFileRef>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DebridFileRef>?): Boolean = size > MAX_ENTRIES
    }

    /** Records [ref] and returns its stream URL. */
    @Synchronized
    fun register(ref: DebridFileRef): String {
        val token = tokenFor(ref)
        refs[token] = ref
        return SCHEME_PREFIX + token
    }

    @Synchronized
    fun get(token: String): DebridFileRef? = refs[token]

    /** `Clear Debrid settings`: every registered file (and its service link) is forgotten. */
    @Synchronized
    fun clear() = refs.clear()

    companion object {
        const val SCHEME = "seastreams-debrid"
        const val SCHEME_PREFIX = "$SCHEME://"
        private const val MAX_ENTRIES = 2_000
        private const val TOKEN_CHARS = 32
        private val TOKEN = Regex("^[0-9a-f]{$TOKEN_CHARS}$")

        fun tokenFor(ref: DebridFileRef): String = sha1Hex(ref.identity).take(TOKEN_CHARS)

        fun isDebrid(url: String): Boolean = url.startsWith(SCHEME_PREFIX)

        /** The token of a `seastreams-debrid://<token>` URL, or `null` for anything else. */
        fun tokenOf(url: String): String? =
            url.takeIf(::isDebrid)?.removePrefix(SCHEME_PREFIX)?.substringBefore('/')?.takeIf { TOKEN.matches(it) }
    }
}
