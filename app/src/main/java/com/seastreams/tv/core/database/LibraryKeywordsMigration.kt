package com.seastreams.tv.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v10 → v11: adds the nullable `library_items.keywordIds` (TMDB keyword ids behind keyword genres such as the
 * Library's `Christmas`). Existing rows read `NULL` ("not looked up yet") and are filled by `LibraryKeywordBackfill`.
 */
object LibraryKeywordsMigration : Migration(10, 11) {

    /** Executed in order; public so the SQL can be exercised against a real SQLite in unit tests. */
    val STATEMENTS: List<String> = listOf(
        "ALTER TABLE `library_items` ADD COLUMN `keywordIds` TEXT DEFAULT NULL",
    )

    override fun migrate(db: SupportSQLiteDatabase) {
        STATEMENTS.forEach(db::execSQL)
    }
}
