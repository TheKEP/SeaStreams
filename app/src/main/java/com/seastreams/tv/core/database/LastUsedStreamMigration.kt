package com.seastreams.tv.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v5 → v6: adds `last_used_streams`, the per-profile `LAST USED` stream of each movie/episode
 * (PRD §6, §9). Nothing is copied: no stream was remembered before v6. Its cascading foreign key
 * to `profiles` removes a profile's rows when the profile is deleted.
 */
object LastUsedStreamMigration : Migration(5, 6) {

    /** Executed in order; public so the SQL can be exercised against a real SQLite in unit tests. */
    val STATEMENTS: List<String> = listOf(
        // Must match the `createSql` Room generated for `LastUsedStreamEntity` in schemas/…/6.json.
        "CREATE TABLE IF NOT EXISTS `last_used_streams` (`profileId` INTEGER NOT NULL, `tmdbId` INTEGER NOT NULL, " +
            "`mediaType` TEXT NOT NULL, `seasonNumber` INTEGER NOT NULL, `episodeNumber` INTEGER NOT NULL, " +
            "`addonUrl` TEXT NOT NULL, `streamUrl` TEXT NOT NULL, `label` TEXT NOT NULL, `description` TEXT NOT NULL, " +
            "`updatedAtMillis` INTEGER NOT NULL, " +
            "PRIMARY KEY(`profileId`, `tmdbId`, `mediaType`, `seasonNumber`, `episodeNumber`), " +
            "FOREIGN KEY(`profileId`) REFERENCES `profiles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_last_used_streams_profileId` ON `last_used_streams` (`profileId`)",
    )

    override fun migrate(db: SupportSQLiteDatabase) {
        STATEMENTS.forEach(db::execSQL)
    }
}
