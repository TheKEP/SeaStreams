package com.seastreams.tv.core.database

import com.seastreams.tv.testutil.exportedSchemaSql
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * Runs [LastUsedStreamUrlMigration]'s real SQL against an in-memory SQLite database built from the exported v8 schema:
 * stored stream URLs (which may carry Debrid keys or signed tokens) are blanked; everything else survives.
 */
class LastUsedStreamUrlMigrationTest {

    private lateinit var db: Connection

    @BeforeEach
    fun setUp() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        exec("PRAGMA foreign_keys = ON")
        exportedSchemaSql(8).forEach(::exec)
    }

    @AfterEach
    fun tearDown() = db.close()

    @Test
    fun `v9 keeps the v8 table shape`() {
        assertEquals(8, LastUsedStreamUrlMigration.startVersion)
        assertEquals(9, LastUsedStreamUrlMigration.endVersion)
        assertEquals(exportedSchemaSql(8), exportedSchemaSql(9))
    }

    @Test
    fun `stored stream URLs are blanked and the rest of each row survives`() {
        exec(
            "INSERT INTO profiles (id, name, avatarIndex, isKids, movieCeiling, tvCeiling, allowUnrated, librarySortOrder, createdAtMillis) " +
                "VALUES (1, 'Adult', 0, 0, NULL, NULL, 0, 'A_TO_Z', 1)",
        )
        exec(
            "INSERT INTO last_used_streams (profileId, tmdbId, mediaType, seasonNumber, episodeNumber, addonUrl, streamUrl, label, description, updatedAtMillis) " +
                "VALUES (1, 9, 'TV', 1, 2, 'https://addon', 'https://addon/resolve/realdebrid/SECRET/file.mkv', 'Addon · 4k', 'File.mkv', 5)",
        )

        LastUsedStreamUrlMigration.STATEMENTS.forEach(::exec)

        assertEquals(
            listOf(listOf<Any?>(1, 9, "https://addon", "", "Addon · 4k", "File.mkv", 5)),
            query("SELECT profileId, tmdbId, addonUrl, streamUrl, label, description, updatedAtMillis FROM last_used_streams"),
        )
        assertEquals(listOf(listOf<Any?>(1, "Adult")), query("SELECT id, name FROM profiles"))
    }

    private fun exec(sql: String) {
        db.createStatement().use { it.execute(sql) }
    }

    private fun query(sql: String): List<List<Any?>> = db.createStatement().use { statement ->
        statement.executeQuery(sql).use { rs ->
            buildList {
                while (rs.next()) add((1..rs.metaData.columnCount).map { rs.getObject(it) })
            }
        }
    }
}
