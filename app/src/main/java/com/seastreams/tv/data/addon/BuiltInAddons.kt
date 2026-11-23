package com.seastreams.tv.data.addon

import com.seastreams.tv.core.database.entity.AddonEntity
import com.seastreams.tv.data.debrid.library.DebridLibrarySearch
import com.seastreams.tv.data.usenet.newznab.UsenetStreamSearch

/**
 * Addons every new profile starts with (PRD §8): SeaStreams' built-in TMDB metadata provider and the
 * official OpenSubtitles v3 Stremio addon, both enabled and scoped to that profile only.
 */
object BuiltInAddons {

    /** Pseudo-URL for the built-in TMDB provider; it is served in-process, never over HTTP. */
    const val TMDB_URL = "builtin://tmdb"
    const val OPENSUBTITLES_URL = "https://opensubtitles-v3.strem.io/manifest.json"

    private const val TMDB_MANIFEST = """{"id":"com.seastreams.tmdb","version":"1.0.0","name":"TMDB Metadata",""" +
        """"description":"Built-in metadata and catalogs from The Movie Database",""" +
        """"resources":["catalog","meta"],"types":["movie","series"],"idPrefixes":["tmdb:"],"catalogs":[]}"""

    private const val OPENSUBTITLES_MANIFEST = """{"id":"org.stremio.opensubtitlesv3","version":"1.0.0",""" +
        """"name":"OpenSubtitles v3","description":"OpenSubtitles v3 Addon for Stremio",""" +
        """"resources":["subtitles"],"types":["movie","series"],"idPrefixes":["tt"],"catalogs":[]}"""

    /**
     * The built-in Usenet stream source (PRD §12–§13): a row in each profile's list so its results take a place in
     * the addon order, added (at the bottom) once Usenet streaming is configured and listed only while it is.
     */
    const val USENET_URL = UsenetStreamSearch.USENET_ADDON_URL

    private const val USENET_MANIFEST = """{"id":"com.seastreams.usenet","version":"1.0.0","name":"Usenet",""" +
        """"description":"Built-in Usenet streams from your indexers and providers",""" +
        """"resources":["stream"],"types":["movie","series"],"idPrefixes":["tt","tmdb:"],"catalogs":[]}"""

    /**
     * The built-in Debrid library source (PRD §14), like [USENET_URL]: added (at the bottom) once `Enable Debrid` is ON
     * with an enabled provider, and listed only while that holds.
     */
    const val DEBRID_URL = DebridLibrarySearch.DEBRID_ADDON_URL

    private const val DEBRID_MANIFEST = """{"id":"com.seastreams.debrid","version":"1.0.0","name":"Debrid",""" +
        """"description":"Built-in streams from the files in your Debrid accounts",""" +
        """"resources":["stream"],"types":["movie","series"],"idPrefixes":["tt","tmdb:"],"catalogs":[]}"""

    /** Built-in entries (TMDB, Usenet, Debrid) cannot be removed; they can be renamed and reordered. */
    fun isProtected(manifestUrl: String): Boolean = manifestUrl == TMDB_URL || manifestUrl == USENET_URL || manifestUrl == DEBRID_URL

    /** TMDB can't be disabled either; Usenet and Debrid can, which turns their results off for that profile only. */
    fun canDisable(manifestUrl: String): Boolean = manifestUrl != TMDB_URL

    /** Built-in stream sources (not Stremio addons): their results come from in-app searches. */
    fun isBuiltInStreamSource(manifestUrl: String): Boolean = manifestUrl == USENET_URL || manifestUrl == DEBRID_URL

    /**
     * Whether a row is listed: the Usenet entry only while Usenet streaming is configured, the Debrid entry only while
     * Debrid is on with an enabled provider. A hidden entry keeps its place, name and on/off state.
     */
    fun isListed(manifestUrl: String, usenetConfigured: Boolean, debridListed: Boolean): Boolean = when (manifestUrl) {
        USENET_URL -> usenetConfigured
        DEBRID_URL -> debridListed
        else -> true
    }

    /** The Debrid entry for [profileId] at [sortOrder]. */
    fun debrid(profileId: Long, sortOrder: Int, nowMillis: Long) = AddonEntity(
        profileId = profileId,
        manifestUrl = DEBRID_URL,
        manifestId = "com.seastreams.debrid",
        name = DebridLibrarySearch.DEBRID_ADDON_NAME,
        customName = null,
        enabled = true,
        sortOrder = sortOrder,
        manifestJson = DEBRID_MANIFEST,
        installedAtMillis = nowMillis,
    )

    /** The Usenet entry for [profileId] at [sortOrder]. */
    fun usenet(profileId: Long, sortOrder: Int, nowMillis: Long) = AddonEntity(
        profileId = profileId,
        manifestUrl = USENET_URL,
        manifestId = "com.seastreams.usenet",
        name = UsenetStreamSearch.USENET_ADDON_NAME,
        customName = null,
        enabled = true,
        sortOrder = sortOrder,
        manifestJson = USENET_MANIFEST,
        installedAtMillis = nowMillis,
    )

    /** [profileId]'s default addons: TMDB first, then OpenSubtitles. */
    fun defaults(profileId: Long, nowMillis: Long): List<AddonEntity> = listOf(
        tmdb(profileId, sortOrder = 0, nowMillis = nowMillis),
        AddonEntity(
            profileId = profileId,
            manifestUrl = OPENSUBTITLES_URL,
            manifestId = "org.stremio.opensubtitlesv3",
            name = "OpenSubtitles v3",
            customName = null,
            enabled = true,
            sortOrder = 1,
            manifestJson = OPENSUBTITLES_MANIFEST,
            installedAtMillis = nowMillis,
        ),
    )

    /** The protected TMDB entry for [profileId] at [sortOrder]. */
    fun tmdb(profileId: Long, sortOrder: Int, nowMillis: Long) = AddonEntity(
        profileId = profileId,
        manifestUrl = TMDB_URL,
        manifestId = "com.seastreams.tmdb",
        name = "TMDB Metadata",
        customName = null,
        enabled = true,
        sortOrder = sortOrder,
        manifestJson = TMDB_MANIFEST,
        installedAtMillis = nowMillis,
    )
}
