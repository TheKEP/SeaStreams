package com.seastreams.tv.usenet.session

import com.seastreams.tv.data.usenet.DEFAULT_PIPELINE_DEPTH
import com.seastreams.tv.usenet.nntp.NntpEndpoint
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder

/**
 * The `servers` of a Stremio `nzbUrl` stream (PRD §12): `nntps://user:pass@host:563/8` (`nntp`/`news` = plain,
 * `nntps`/`snews` = TLS; the path is the connection count). Anything malformed, or holding a line break that could
 * inject an NNTP command, is skipped. The servers keep their listed order as priorities; none is a backup.
 */
object AddonServers {
    private const val DEFAULT_CONNECTIONS = 4
    private const val MAX_CONNECTIONS = 50
    private const val TLS_PORT = 563
    private const val PLAIN_PORT = 119

    fun parse(urls: List<String>): List<NntpEndpoint> = urls.mapIndexedNotNull { index, raw -> endpoint(index, raw) }

    private fun endpoint(index: Int, raw: String): NntpEndpoint? {
        val uri = try {
            URI(raw.trim())
        } catch (e: URISyntaxException) {
            return null
        }
        val tls = when (uri.scheme?.lowercase()) {
            "nntps", "snews" -> true
            "nntp", "news" -> false
            else -> return null
        }
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val (user, pass) = uri.rawUserInfo?.let { info ->
            decode(info.substringBefore(':')) to decode(info.substringAfter(':', ""))
        } ?: ("" to "")
        val connections = uri.path.orEmpty().trim('/').toIntOrNull()?.coerceIn(1, MAX_CONNECTIONS) ?: DEFAULT_CONNECTIONS
        return try {
            NntpEndpoint(
                id = -(index + 1L),
                name = host,
                host = host.lowercase(),
                port = if (uri.port > 0) uri.port else if (tls) TLS_PORT else PLAIN_PORT,
                tls = tls,
                username = user,
                password = pass,
                maxConnections = connections,
                pipelineDepth = DEFAULT_PIPELINE_DEPTH,
                priority = index,
                isBackup = false,
            )
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun decode(text: String): String = try {
        URLDecoder.decode(text.replace("+", "%2B"), "UTF-8")
    } catch (e: IllegalArgumentException) {
        text
    }
}
