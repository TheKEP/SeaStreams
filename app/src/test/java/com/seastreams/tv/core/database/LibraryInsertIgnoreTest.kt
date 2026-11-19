package com.seastreams.tv.core.database

import com.seastreams.tv.testutil.exportedSchemaSql
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * Runs the SQL Room generates for `LibraryDao.insertAll` (`OnConflictStrategy.IGNORE` →
 * `INSERT OR IGNORE`) against the current exported schema, proving at the database level that an
 * MDBList import can never duplicate, overwrite, re-date or delete a saved title, nor touch its
 * watch progress, and that the key is per profile.
 */
class LibraryInsertIgnoreTest {

    private lateinit var db: Connection

    @BeforeEach
    fun setUp() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        exec("PRAGMA foreign_keys = ON")
        exportedSchemaSql(SCHEMA_VERSION).forEach(::exec)
        insertProfile(1)
        insertProfile(2)
    }

    @AfterEach
    fun tearDown() = db.close()

    @Test
    fun `re-inserting a saved title is ignored and keeps the original row and progress`() {
        insertIgnore(profileId = 1, tmdbId = 7, title = "Original", addedAt = 1_000)
        exec(
            "INSERT INTO watch_progress (profileId, tmdbId, mediaType, seasonNumber, episodeNumber, title, positionMs, durationMs, updatedAtMillis, dismissedFromContinueWatching) " +
                "VALUES (1, 7, 'MOVIE', 0, 0, 'Original', 40000, 100000, 5, 1)",
        )

        val changed = insertIgnore(profileId = 1, tmdbId = 7, title = "From MDBList", addedAt = 9_000)

        assertEquals(0, changed, "conflicting row is skipped")
        assertEquals(listOf(listOf<Any?>(1, 7, "Original", 1_000)), query("SELECT profileId, tmdbId, title, addedAtMillis FROM library_items"))
        assertEquals(
            listOf(listOf<Any?>(40_000, 100_000, 5, 1)),
            query("SELECT positionMs, durationMs, updatedAtMillis, dismissedFromContinueWatching FROM watch_progress"),
        )
    }

    @Test
    fun `the same title inserts independently for another profile`() {
        insertIgnore(profileId = 1, tmdbId = 7, title = "A's", addedAt = 1_000)

        val changed = insertIgnore(profileId = 2, tmdbId = 7, title = "B's", addedAt = 9_000)

        assertEquals(1, changed)
        assertEquals(
            listOf(listOf<Any?>(1, "A's", 1_000), listOf<Any?>(2, "B's", 9_000)),
            query("SELECT profileId, title, addedAtMillis FROM library_items ORDER BY profileId"),
        )
    }

    /** Mirrors Room's IGNORE insert for `library_items`; returns the number of rows written. */
    private fun insertIgnore(profileId: Long, tmdbId: Int, title: String, addedAt: Long): Int =
        db.prepareStatement(
            "INSERT OR IGNORE INTO `library_items` (`profileId`,`tmdbId`,`mediaType`,`title`,`posterPath`,`certification`,`genreIds`,`addedAtMillis`) " +
                "VALUES (?,?,'MOVIE',?,NULL,'PG','18',?)",
        ).use { statement ->
            statement.setLong(1, profileId)
            statement.setInt(2, tmdbId)
            statement.setString(3, title)
            statement.setLong(4, addedAt)
            statement.executeUpdate()
        }

    private fun insertProfile(id: Long) = exec(
        "INSERT INTO profiles (id, name, avatarIndex, isKids, movieCeiling, tvCeiling, allowUnrated, librarySortOrder, createdAtMillis) " +
            "VALUES ($id, 'P$id', 0, 0, NULL, NULL, 0, 'A_TO_Z', $id)",
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

    private companion object {
        /** Current `SeaStreamsDatabase` version (latest exported schema). */
        const val SCHEMA_VERSION = 6
    }
}
