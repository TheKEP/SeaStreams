package com.seastreams.tv.core.database

import com.seastreams.tv.testutil.exportedSchemaSql
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager

/** Runs [LibraryKeywordsMigration]'s SQL on a SQLite built from the exported v10 schema and checks it against v11. */
class LibraryKeywordsMigrationTest {

    private lateinit var db: Connection

    @BeforeEach
    fun setUp() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        exec("PRAGMA foreign_keys = ON")
        exportedSchemaSql(10).forEach(::exec)
    }

    @AfterEach
    fun tearDown() = db.close()

    @Test
    fun `library_items ends up with exactly v11's columns`() {
        assertEquals(10, LibraryKeywordsMigration.startVersion)
        assertEquals(11, LibraryKeywordsMigration.endVersion)
        LibraryKeywordsMigration.STATEMENTS.forEach(::exec)
        val migrated = columns()

        exec("DROP TABLE library_items")
        exportedSchemaSql(11).filter { it.contains("TABLE IF NOT EXISTS `library_items`") }.forEach(::exec)

        assertEquals(columns(), migrated)
    }

    @Test
    fun `saved titles survive with keywords not looked up yet`() {
        exec(
            "INSERT INTO profiles (id, name, avatarIndex, isKids, movieCeiling, tvCeiling, allowUnrated, librarySortOrder, createdAtMillis) " +
                "VALUES (1, 'Ken', 0, 0, NULL, NULL, 0, 'A_TO_Z', 1)",
        )
        exec(
            "INSERT INTO library_items (profileId, tmdbId, mediaType, title, posterPath, certification, genreIds, addedAtMillis) " +
                "VALUES (1, 771, 'MOVIE', 'Home Alone', NULL, 'PG', '35,10751', 5)",
        )

        LibraryKeywordsMigration.STATEMENTS.forEach(::exec)

        assertEquals(listOf(listOf<Any?>(771, "35,10751", null)), query("SELECT tmdbId, genreIds, keywordIds FROM library_items"))
    }

    /** (name, type, notnull, default, pk) of every `library_items` column, by name. */
    private fun columns(): List<List<Any?>> = query("PRAGMA table_info(library_items)").map { it.drop(1) }.sortedBy { it[0].toString() }

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
