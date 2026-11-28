package com.seastreams.tv.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v9 → v10: adds `next_up_dismissals` (Milestone 13: a Next Up card removed from Continue Watching stays hidden until
 * the show is watched again or a newer episode airs). Nothing is copied and no other table changes.
 */
object NextUpDismissalsMigration : Migration(9, 10) {

    /** Executed in order; public so the SQL can be exercised against a real SQLite in unit tests. */
    val STATEMENTS: List<String> = listOf(
        // Must match the `createSql` Room generated for the entity in schemas/…/10.json.
        "CREATE TABLE IF NOT EXISTS `next_up_dismissals` (`profileId` INTEGER NOT NULL, `tmdbId` INTEGER NOT NULL, " +
            "`seasonNumber` INTEGER NOT NULL, `episodeNumber` INTEGER NOT NULL, `lastAiredSeason` INTEGER, " +
            "`lastAiredEpisode` INTEGER, `dismissedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`profileId`, `tmdbId`), " +
            "FOREIGN KEY(`profileId`) REFERENCES `profiles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
    )

    override fun migrate(db: SupportSQLiteDatabase) {
        STATEMENTS.forEach(db::execSQL)
    }
}
