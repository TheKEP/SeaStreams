package com.seastreams.tv.data.usenet

import com.seastreams.tv.data.UserFacingException
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/** A provider as shown on the TV and in the portal: never carries the password itself. */
data class UsenetProvider(
    val id: Long,
    val name: String,
    val host: String,
    val port: Int,
    val tls: Boolean,
    val username: String,
    val hasPassword: Boolean,
    /** A password is stored but can't be decrypted on this device (shown as *Password needed*). */
    val passwordNeeded: Boolean,
    val maxConnections: Int,
    val pipelineDepth: Int,
    val priority: Int,
    val isBackup: Boolean,
    val enabled: Boolean,
)

/** An indexer as shown on the TV and in the portal: never carries the API key itself. */
data class NewznabIndexer(
    val id: Long,
    val name: String,
    val baseUrl: String,
    val apiPath: String,
    val hasApiKey: Boolean,
    val apiKeyNeeded: Boolean,
    val priority: Int,
    val backupOnly: Boolean,
    val enabled: Boolean,
)

/** Fields of a provider being added or edited. A `null` or blank [password] on edit keeps the stored one. */
data class ProviderDraft(
    val name: String,
    val host: String,
    val port: Int = 563,
    val tls: Boolean = true,
    val username: String = "",
    val password: String? = null,
    val maxConnections: Int = DEFAULT_MAX_CONNECTIONS,
    val pipelineDepth: Int = DEFAULT_PIPELINE_DEPTH,
    val priority: Int = 0,
    val isBackup: Boolean = false,
    val enabled: Boolean = true,
)

/** Fields of an indexer being added or edited. A `null` or blank [apiKey] on edit keeps the stored one. */
data class IndexerDraft(
    val name: String,
    val baseUrl: String,
    val apiPath: String = DEFAULT_API_PATH,
    val apiKey: String? = null,
    val priority: Int = 0,
    val backupOnly: Boolean = false,
    val enabled: Boolean = true,
)

const val DEFAULT_MAX_CONNECTIONS = 20
const val DEFAULT_PIPELINE_DEPTH = 2
const val DEFAULT_API_PATH = "/api"
val MAX_CONNECTIONS_RANGE = 1..100
val PIPELINE_DEPTH_RANGE = 1..16
val PRIORITY_RANGE = 0..99
private val PORT_RANGE = 1..65_535
private const val MAX_NAME = 60
private const val MAX_HOST = 253
private const val MAX_SECRET = 256

/**
 * Validation for the Sources forms (PRD §12). Every message is shown verbatim on the TV or in the
 * portal. CR/LF are rejected everywhere a value may end up in an NNTP command or HTTP header
 * (`AUTHINFO USER …`), so a crafted value can never inject a second command.
 */
object UsenetSourceValidation {

    /** The draft trimmed and normalised (lower-case host), or a [UserFacingException]. */
    fun provider(draft: ProviderDraft): ProviderDraft {
        val name = requireName(draft.name, "provider")
        val host = draft.host.trim().lowercase(Locale.ROOT)
        when {
            host.isEmpty() -> fail("Enter the provider's server address")
            "://" in host || '/' in host -> fail("Enter just the server address, e.g. news.example.com")
            host.length > MAX_HOST || host.any { it.isWhitespace() || it.isISOControl() } -> fail("That server address isn't valid")
        }
        if (draft.port !in PORT_RANGE) fail("The port must be between 1 and 65535")
        val username = draft.username.trim()
        requireSafe(username, "username")
        draft.password?.let { requireSafe(it, "password") }
        if (draft.maxConnections !in MAX_CONNECTIONS_RANGE) fail("Max connections must be between 1 and 100")
        if (draft.pipelineDepth !in PIPELINE_DEPTH_RANGE) fail("Pipeline depth must be between 1 and 16")
        if (draft.priority !in PRIORITY_RANGE) fail("Priority must be between 0 and 99")
        return draft.copy(name = name, host = host, username = username)
    }

    /** The draft with [IndexerDraft.baseUrl] reduced to scheme + host (+ port) and a normalised API path. */
    fun indexer(draft: IndexerDraft): IndexerDraft {
        val name = requireName(draft.name, "indexer")
        val uri = try {
            URI(draft.baseUrl.trim())
        } catch (e: URISyntaxException) {
            fail("Enter the indexer address, e.g. https://indexer.example")
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if ((scheme != "https" && scheme != "http") || uri.host.isNullOrEmpty()) {
            fail("Enter the indexer address, e.g. https://indexer.example")
        }
        val port = if (uri.port > 0) ":${uri.port}" else ""
        // A full API URL pasted into the address field moves its path into the API path.
        val pastedPath = uri.rawPath.orEmpty().trimEnd('/')
        val apiPath = draft.apiPath.trim().ifEmpty { DEFAULT_API_PATH }
            .let { if (pastedPath.isNotEmpty() && it == DEFAULT_API_PATH) pastedPath else it }
        if (!apiPath.startsWith("/") || apiPath.any { it.isWhitespace() || it.isISOControl() || it == '?' || it == '#' }) {
            fail("The API path must start with / (usually /api)")
        }
        draft.apiKey?.let { requireSafe(it.trim(), "API key") }
        if (draft.priority !in PRIORITY_RANGE) fail("Priority must be between 0 and 99")
        return draft.copy(
            name = name,
            baseUrl = "$scheme://${uri.host.lowercase(Locale.ROOT)}$port",
            apiPath = apiPath,
            apiKey = draft.apiKey?.trim(),
        )
    }

    private fun requireName(raw: String, what: String): String {
        val name = raw.trim()
        if (name.isEmpty()) fail("Give the $what a name")
        if (name.length > MAX_NAME) fail("Names can be at most $MAX_NAME characters")
        return name
    }

    private fun requireSafe(value: String, what: String) {
        if (value.length > MAX_SECRET) fail("The $what is too long")
        if (value.any { it == '\r' || it == '\n' || it == '\u0000' }) fail("The $what can't contain line breaks")
    }

    private fun fail(message: String): Nothing = throw UserFacingException(message)
}

/** [order] with [id] moved by [offset] positions (negative = up), clamped to the list bounds. */
internal fun moveId(order: List<Long>, id: Long, offset: Int): List<Long> {
    val from = order.indexOf(id)
    if (from < 0 || offset == 0) return order
    val to = (from + offset).coerceIn(0, order.lastIndex)
    if (to == from) return order
    return order.toMutableList().apply { add(to, removeAt(from)) }
}
