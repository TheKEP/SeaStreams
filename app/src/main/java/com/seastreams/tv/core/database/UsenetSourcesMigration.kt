package com.seastreams.tv.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v6 → v7: adds the app-wide Usenet Sources tables (PRD §12), `usenet_providers` and
 * `newznab_indexers`. Nothing is copied (no sources existed before v7) and there is no profile
 * foreign key: every profile shares the same providers and indexers.
 */
object UsenetSourcesMigration : Migration(6, 7) {

    /** Executed in order; public so the SQL can be exercised against a real SQLite in unit tests. */
    val STATEMENTS: List<String> = listOf(
        // Must match the `createSql` Room generated for the entities in schemas/…/7.json.
        "CREATE TABLE IF NOT EXISTS `usenet_providers` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`name` TEXT NOT NULL, `host` TEXT NOT NULL, `port` INTEGER NOT NULL, `tls` INTEGER NOT NULL, " +
            "`username` TEXT NOT NULL, `passwordCipher` TEXT, `maxConnections` INTEGER NOT NULL, " +
            "`pipelineDepth` INTEGER NOT NULL, `priority` INTEGER NOT NULL, `isBackup` INTEGER NOT NULL, " +
            "`enabled` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS `index_usenet_providers_sortOrder` ON `usenet_providers` (`sortOrder`)",
        "CREATE TABLE IF NOT EXISTS `newznab_indexers` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`name` TEXT NOT NULL, `baseUrl` TEXT NOT NULL, `apiPath` TEXT NOT NULL, `apiKeyCipher` TEXT, " +
            "`priority` INTEGER NOT NULL, `backupOnly` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, " +
            "`sortOrder` INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS `index_newznab_indexers_sortOrder` ON `newznab_indexers` (`sortOrder`)",
    )

    override fun migrate(db: SupportSQLiteDatabase) {
        STATEMENTS.forEach(db::execSQL)
    }
}
