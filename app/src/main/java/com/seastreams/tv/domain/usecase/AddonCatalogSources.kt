package com.seastreams.tv.domain.usecase

import com.seastreams.tv.core.database.entity.AddonEntity
import com.seastreams.tv.core.network.InvalidManifestException
import com.seastreams.tv.core.network.StremioManifestParser
import com.seastreams.tv.domain.model.CatalogRowSpec
import com.seastreams.tv.domain.model.CatalogSource
import com.seastreams.tv.domain.model.MediaType

/**
 * Home rows for the catalogs that enabled Stremio addons declare in their `manifest.json` (PRD §3, §8).
 * These rows are Home-only; the Movies and TV Shows tabs never include them.
 */
object AddonCatalogSources {

    /**
     * One row per showable catalog of [enabledAddons], in installed addon order and then each
     * manifest's declared catalog order. Skipped: in-process built-ins (`builtin://`, no HTTP
     * endpoint), manifests that no longer parse, catalog types other than `movie`/`series`, and
     * catalogs that need an extra parameter (search/genre-only) to be fetched.
     */
    fun rowSpecs(enabledAddons: List<AddonEntity>): List<CatalogRowSpec> =
        enabledAddons
            .filter { it.enabled && it.manifestUrl.startsWith("http", ignoreCase = true) }
            .sortedBy { it.sortOrder }
            .flatMap { addon ->
                val manifest = try {
                    StremioManifestParser.parse(addon.manifestJson)
                } catch (e: InvalidManifestException) {
                    return@flatMap emptyList()
                }
                manifest.catalogs.mapNotNull { catalog ->
                    val mediaType = mediaTypeOf(catalog.type) ?: return@mapNotNull null
                    if (catalog.requiresExtra) return@mapNotNull null
                    CatalogRowSpec(
                        id = "addon:${addon.manifestUrl}:${catalog.type}:${catalog.id}",
                        title = "${catalog.name} (${typeLabel(mediaType)})",
                        source = CatalogSource.Addon(addon.manifestUrl, catalog.id, mediaType),
                    )
                }
            }
            .distinctBy { it.id }

    /** Stremio `movie`/`series` → [MediaType]; any other type (`channel`, `tv`, …) → `null`. */
    fun mediaTypeOf(stremioType: String): MediaType? = MediaType.entries.firstOrNull { it.stremioType == stremioType }

    private fun typeLabel(mediaType: MediaType): String = when (mediaType) {
        MediaType.MOVIE -> "Movies"
        MediaType.TV -> "Shows"
    }
}
