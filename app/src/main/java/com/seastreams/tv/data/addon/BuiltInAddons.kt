package com.seastreams.tv.data.addon

import com.seastreams.tv.core.database.entity.AddonEntity

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

    /** Built-in entries cannot be removed or disabled from the portal; they can be renamed/reordered. */
    fun isProtected(manifestUrl: String): Boolean = manifestUrl == TMDB_URL

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
