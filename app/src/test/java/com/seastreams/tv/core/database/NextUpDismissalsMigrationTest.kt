package com.seastreams.tv.core.database

import com.seastreams.tv.testutil.exportedSchemaSql
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/** Runs [NextUpDismissalsMigration]'s SQL on a SQLite built from the exported v9 schema and checks it against v10. */
class NextUpDismissalsMigrationTest {

    private lateinit var db: Connection

    @BeforeEach
    fun setUp() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        exec("PRAGMA foreign_keys = ON")
        exportedSchemaSql(9).forEach(::exec)
    }

    @AfterEach
    fun tearDown() = db.close()

    @Test
    fun `the migration SQL is Room's exported v10 createSql verbatim`() {
        assertEquals(exportedSchemaSql(10).filter { it.contains("`next_up_dismissals`") }, NextUpDismissalsMigration.STATEMENTS)
        assertEquals(9, NextUpDismissalsMigration.startVersion)
        assertEquals(10, NextUpDismissalsMigration.endVersion)
    }

    @Test
    fun `existing data survives, rows are one per show per profile and go with their profile`() {
        insertProfile(1, "Adult")
        insertProfile(2, "Kid")
        exec(
            "INSERT INTO watch_progress (profileId, tmdbId, mediaType, seasonNumber, episodeNumber, title, episodeTitle, posterPath, " +
                "backdropPath, positionMs, durationMs, updatedAtMillis, dismissedFromContinueWatching) VALUES (1, 7, 'TV', 1, 3, 'Show', NULL, NULL, NULL, 95, 100, 1, 0)",
        )

        NextUpDismissalsMigration.STATEMENTS.forEach(::exec)
        insertDismissal(1, 7)
        insertDismissal(2, 7)
        assertThrows<SQLException> { insertDismissal(1, 7) }
        exec("DELETE FROM profiles WHERE id = 2")

        assertEquals(listOf(listOf<Any?>(1, 7, null)), query("SELECT profileId, tmdbId, lastAiredSeason FROM next_up_dismissals"))
        assertEquals(listOf(listOf<Any?>(1, 7)), query("SELECT profileId, tmdbId FROM watch_progress"))
    }

    private fun insertProfile(id: Long, name: String) = exec(
        "INSERT INTO profiles (id, name, avatarIndex, isKids, movieCeiling, tvCeiling, allowUnrated, librarySortOrder, createdAtMillis) " +
            "VALUES ($id, '$name', 0, 0, NULL, NULL, 0, 'A_TO_Z', $id)",
    )

    private fun insertDismissal(profileId: Long, tmdbId: Int) = exec(
        "INSERT INTO next_up_dismissals (profileId, tmdbId, seasonNumber, episodeNumber, lastAiredSeason, lastAiredEpisode, dismissedAtMillis) " +
            "VALUES ($profileId, $tmdbId, 1, 4, NULL, NULL, 10)",
    )

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
