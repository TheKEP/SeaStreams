package com.seastreams.tv.data.usenet.headers

import com.seastreams.tv.BuildConfig

private const val RSS_ACCEPT = "application/rss+xml, text/rss+xml, application/xml, text/xml"
private const val ANY_ACCEPT = "*/*"
private const val ALPINE = "(alpine 3.24.4)"

/**
 * Request header presets for indexer searches and NZB downloads (PRD §12). `{v}` in [userAgentTemplate]
 * is the tool's version: the latest one fetched by [HeaderVersionUpdater], else [fallbackVersion].
 * [githubRepo] is where that version comes from (`null` for SeaStreams itself). `Accept-Encoding` is
 * deliberately absent: OkHttp adds `gzip` and decompresses transparently only when it sets it itself.
 */
enum class HeaderPreset(
    val label: String,
    val githubRepo: String?,
    val fallbackVersion: String,
    private val userAgentTemplate: String,
    private val accept: String,
) {
    PROWLARR("Prowlarr", "Prowlarr/Prowlarr", "2.7.0.5723", "Prowlarr/{v} $ALPINE", RSS_ACCEPT),
    NZBHYDRA2("NZBHydra2", "theotherp/nzbhydra2", "9.3.1", "NZBHydra2 {v}", RSS_ACCEPT),
    SONARR("Sonarr", "Sonarr/Sonarr", "4.0.20.3014", "Sonarr/{v} $ALPINE", RSS_ACCEPT),
    RADARR("Radarr", "Radarr/Radarr", "7.0.3.10918", "Radarr/{v} $ALPINE", RSS_ACCEPT),
    SABNZBD("SABnzbd", "sabnzbd/sabnzbd", "5.1.3", "SABnzbd/{v}", ANY_ACCEPT),
    NZBGET("NZBGet", "nzbgetcom/nzbget", "26.3", "nzbget/{v}", ANY_ACCEPT),
    SEASTREAMS("SeaStreams", null, BuildConfig.VERSION_NAME, "SeaStreams/{v}", ANY_ACCEPT),
    ;

    /** The version sent for this preset: a fetched one when known, else the built-in fallback. */
    fun version(fetched: Map<HeaderPreset, String>): String = fetched[this] ?: fallbackVersion

    /** `Prowlarr 2.7.0.5723`: the row value shown on the TV and in the portal. */
    fun displayLabel(fetched: Map<HeaderPreset, String>): String = "$label ${version(fetched)}"

    /** The headers a search or download request using this preset sends. */
    fun headers(fetched: Map<HeaderPreset, String>): Map<String, String> = mapOf(
        "User-Agent" to userAgentTemplate.replace("{v}", version(fetched)),
        "Accept" to accept,
    )

    companion object {
        val DEFAULT_SEARCH = PROWLARR
        val DEFAULT_DOWNLOAD = SABNZBD

        /** Presets whose version is looked up on GitHub. */
        val UPDATABLE: List<HeaderPreset> = entries.filter { it.githubRepo != null }
    }
}

/** Which requests a header preset applies to. */
enum class HeaderUse(val title: String) {
    SEARCH("Search request headers"),
    DOWNLOAD("Download request headers"),
}

/** The saved header choices plus the latest versions fetched from GitHub ([checkedAtMillis] = last successful check). */
data class RequestHeaderSettings(
    val search: HeaderPreset = HeaderPreset.DEFAULT_SEARCH,
    val download: HeaderPreset = HeaderPreset.DEFAULT_DOWNLOAD,
    val versions: Map<HeaderPreset, String> = emptyMap(),
    val checkedAtMillis: Long? = null,
) {
    fun presetFor(use: HeaderUse): HeaderPreset = when (use) {
        HeaderUse.SEARCH -> search
        HeaderUse.DOWNLOAD -> download
    }
}

private val RELEASE_TAG = Regex("""^v?(\d+(?:\.\d+){1,3})$""")

/**
 * The plain version in a GitHub release `tag_name` (`v2.7.0.5723` → `2.7.0.5723`), or `null` for
 * anything else, so a strange tag can never inject text into a `User-Agent` header.
 */
fun releaseVersion(tagName: String?): String? = tagName?.trim()?.let { RELEASE_TAG.matchEntire(it)?.groupValues?.get(1) }
