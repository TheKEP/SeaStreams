package com.seastreams.tv.data.usenet.newznab

import java.io.IOException

/**
 * One enabled indexer with its decrypted API key, as searches use it. Never persisted or logged:
 * [toString] leaves the key out, and the key is added to a request only at send time.
 */
data class NewznabEndpoint(
    val id: Long,
    val name: String,
    /** Base URL + API path, e.g. `https://api.nzbgeek.info/api`. */
    val apiUrl: String,
    val apiKey: String,
    val priority: Int,
    val backupOnly: Boolean,
) {
    override fun toString(): String = "NewznabEndpoint($name, $apiUrl, priority=$priority, backupOnly=$backupOnly)"
}

/** One search mode from `t=caps` (`<search>`, `<tv-search>`, `<movie-search>`). */
data class NewznabSearchMode(val available: Boolean, val params: Set<String>) {
    fun supports(param: String): Boolean = available && param in params
}

/** What an indexer can search (`t=caps`). A mode the indexer doesn't list counts as `q`-only. */
data class NewznabCaps(
    val search: NewznabSearchMode = NewznabSearchMode(true, setOf("q")),
    val tvSearch: NewznabSearchMode = NewznabSearchMode(false, emptySet()),
    val movieSearch: NewznabSearchMode = NewznabSearchMode(false, emptySet()),
    /** Top-level category ids, e.g. `2000`, `5000`. */
    val categories: Set<Int> = emptySet(),
)

/** One `<item>` of a search result. [attributes] are the `newznab:attr` pairs, lower-case names. */
data class NewznabItem(
    val title: String,
    val guid: String,
    /** The NZB download link (`<enclosure url>`, else `<link>`). Holds the API key: never shown or stored. */
    val nzbUrl: String,
    val sizeBytes: Long,
    val publishedMillis: Long?,
    val attributes: Map<String, String>,
) {
    val imdbId: String? get() = attributes["imdb"]?.trim()?.trimStart('t')?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }

    /** `newznab:attr name="password"`: `1`/`2` = passworded or contains a password-protected archive. */
    val passworded: Boolean get() = attributes["password"]?.trim().let { it == "1" || it == "2" }

    /** Leaves [nzbUrl] (and its API key) out. */
    override fun toString(): String = "NewznabItem($title, guid=$guid, size=$sizeBytes)"
}

/**
 * A Newznab error response (`<error code="…" description="…"/>`) or an HTTP failure, worded for a toast.
 * Codes 100–104 are account problems (bad key, suspended, no API access, registration): retrying won't help.
 */
class NewznabException(val code: Int?, message: String) : IOException(message) {
    val accountProblem: Boolean get() = code != null && code in ACCOUNT_CODES

    companion object {
        private val ACCOUNT_CODES = 100..104

        fun fromCode(code: Int, description: String?): NewznabException {
            val text = when (code) {
                100 -> "The indexer rejected the API key"
                101 -> "The indexer account is suspended"
                102 -> "The indexer account isn't allowed to use the API"
                103 -> "The indexer denied the registration"
                104 -> "The indexer isn't accepting registrations"
                in 200..203 -> "The indexer didn't understand the search"
                in 500..599, 429 -> "The indexer's request limit is reached"
                else -> description?.take(MAX_DESCRIPTION)?.let { "The indexer said: $it" } ?: "The indexer returned error $code"
            }
            return NewznabException(code, text)
        }

        private const val MAX_DESCRIPTION = 120
    }
}
