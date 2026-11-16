package com.seastreams.tv.ui.components

/** Pure display formatting for title metadata (kept free of Compose so it is unit-testable). */
object MetadataFormat {

    /** `99` → `1h 39m`, `120` → `2h`, `45` → `45m`; `null` for unknown/zero runtimes. */
    fun runtime(minutes: Int?): String? {
        if (minutes == null || minutes <= 0) return null
        val hours = minutes / MINUTES_PER_HOUR
        val rest = minutes % MINUTES_PER_HOUR
        return when {
            hours == 0 -> "${rest}m"
            rest == 0 -> "${hours}h"
            else -> "${hours}h ${rest}m"
        }
    }

    /** `S7:E10` for an episode; `null` for movies (season/episode `0`). */
    fun episodeBadge(seasonNumber: Int, episodeNumber: Int): String? =
        if (seasonNumber > 0 && episodeNumber > 0) "S$seasonNumber:E$episodeNumber" else null

    private const val MINUTES_PER_HOUR = 60
}
