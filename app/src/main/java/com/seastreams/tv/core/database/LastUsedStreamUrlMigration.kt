package com.seastreams.tv.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v8 → v9: `last_used_streams.streamUrl` holds a SHA-256 fingerprint of the stream URL from now on, never the URL
 * itself (debrid and proxy addons put API keys and signed tokens in stream URLs). URLs stored before v9 can't be
 * hashed in SQL, so they are blanked: those rows still match on label and description (`lastUsedIndex` tiers 2–5).
 * The table's shape is unchanged.
 */
object LastUsedStreamUrlMigration : Migration(8, 9) {

    /** Executed in order; public so the SQL can be exercised against a real SQLite in unit tests. */
    val STATEMENTS: List<String> = listOf("UPDATE `last_used_streams` SET `streamUrl` = ''")

    override fun migrate(db: SupportSQLiteDatabase) {
        STATEMENTS.forEach(db::execSQL)
    }
}
