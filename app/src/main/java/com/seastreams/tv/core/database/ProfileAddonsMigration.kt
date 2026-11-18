package com.seastreams.tv.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v4 → v5: Stremio addons move from one app-wide list to one list per profile.
 *
 * The `addons` table is rebuilt with a `(profileId, manifestUrl)` key and a cascading foreign key
 * to `profiles`, and every existing addon row is copied into every existing profile unchanged
 * (custom name, enabled state, order, install time), so no profile loses an addon. It runs inside
 * Room's migration transaction, before any screen can read the table. With no profiles yet, the
 * legacy rows are dropped: each profile receives its own defaults when it is created.
 */
object ProfileAddonsMigration : Migration(4, 5) {

    /** Executed in order; public so the SQL can be exercised against a real SQLite in unit tests. */
    val STATEMENTS: List<String> = listOf(
        // Must match the `createSql` Room generated for `AddonEntity` in schemas/…/5.json.
        "CREATE TABLE IF NOT EXISTS `addons_new` (`profileId` INTEGER NOT NULL, `manifestUrl` TEXT NOT NULL, " +
            "`manifestId` TEXT NOT NULL, `name` TEXT NOT NULL, `customName` TEXT, `enabled` INTEGER NOT NULL, " +
            "`sortOrder` INTEGER NOT NULL, `manifestJson` TEXT NOT NULL, `installedAtMillis` INTEGER NOT NULL, " +
            "PRIMARY KEY(`profileId`, `manifestUrl`), FOREIGN KEY(`profileId`) REFERENCES `profiles`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE )",
        "INSERT INTO `addons_new` (`profileId`, `manifestUrl`, `manifestId`, `name`, `customName`, `enabled`, " +
            "`sortOrder`, `manifestJson`, `installedAtMillis`) " +
            "SELECT p.`id`, a.`manifestUrl`, a.`manifestId`, a.`name`, a.`customName`, a.`enabled`, " +
            "a.`sortOrder`, a.`manifestJson`, a.`installedAtMillis` FROM `addons` AS a CROSS JOIN `profiles` AS p",
        "DROP TABLE `addons`",
        "ALTER TABLE `addons_new` RENAME TO `addons`",
        "CREATE INDEX IF NOT EXISTS `index_addons_profileId` ON `addons` (`profileId`)",
        "CREATE INDEX IF NOT EXISTS `index_addons_profileId_sortOrder` ON `addons` (`profileId`, `sortOrder`)",
    )

    override fun migrate(db: SupportSQLiteDatabase) {
        STATEMENTS.forEach(db::execSQL)
    }
}
