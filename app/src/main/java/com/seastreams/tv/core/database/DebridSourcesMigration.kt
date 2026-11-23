package com.seastreams.tv.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v7 → v8: adds the app-wide Debrid accounts table (PRD §14), `debrid_providers`. Nothing is copied (no Debrid
 * accounts existed before v8) and nothing else changes, so every Usenet provider, indexer and addon row survives.
 */
object DebridSourcesMigration : Migration(7, 8) {

    /** Executed in order; public so the SQL can be exercised against a real SQLite in unit tests. */
    val STATEMENTS: List<String> = listOf(
        // Must match the `createSql` Room generated for the entity in schemas/…/8.json.
        "CREATE TABLE IF NOT EXISTS `debrid_providers` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`providerType` TEXT NOT NULL, `name` TEXT NOT NULL, `authMethod` TEXT NOT NULL, `credentialCipher` TEXT NOT NULL, " +
            "`refreshTokenCipher` TEXT, `clientIdCipher` TEXT, `clientSecretCipher` TEXT, `expiresAtMillis` INTEGER, " +
            "`signInNeeded` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_debrid_providers_providerType` ON `debrid_providers` (`providerType`)",
        "CREATE INDEX IF NOT EXISTS `index_debrid_providers_sortOrder` ON `debrid_providers` (`sortOrder`)",
    )

    override fun migrate(db: SupportSQLiteDatabase) {
        STATEMENTS.forEach(db::execSQL)
    }
}
