package com.seastreams.tv.core.database

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Runs [LastUsedStreamMigration]'s real SQL against an in-memory SQLite database built from the
 * exported v5 schema, and checks the result against the exported v6 schema, per-profile keys and
 * the cascade on profile deletion.
 */
class LastUsedStreamMigrationTest {

    private lateinit var db: Connection

    @BeforeEach
    fun setUp() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        exec("PRAGMA foreign_keys = ON")
        schema(5).forEach(::exec)
    }

    @AfterEach
    fun tearDown() = db.close()

    @Test
    fun `migrated table matches the Room v6 schema`() {
        migrate()

        // Room validates columns, keys and foreign keys on open; compare them with a table built from v6's createSql.
        exec(schema(6).first { it.contains("TABLE IF NOT EXISTS `last_used_streams`") }.replace("`last_used_streams`", "`expected`"))
        assertEquals(query("PRAGMA table_info(`expected`)"), query("PRAGMA table_info(`last_used_streams`)"))
        assertEquals(query("PRAGMA foreign_key_list(`expected`)"), query("PRAGMA foreign_key_list(`last_used_streams`)"))
        val indices = query("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'last_used_streams' AND sql IS NOT NULL")
        assertEquals(listOf(listOf<Any?>("index_last_used_streams_profileId")), indices)
        assertEquals(listOf("profileId"), query("PRAGMA index_info(`index_last_used_streams_profileId`)").map { it[2] })
        // The migration's SQL is Room's exported createSql verbatim.
        val exported = schema(6).filter { it.contains("`last_used_streams`") }
        assertEquals(exported, LastUsedStreamMigration.STATEMENTS)
    }

    @Test
    fun `existing v5 data survives the migration`() {
        insertProfile(1, "Adult")
        exec(
            "INSERT INTO watch_progress (profileId, tmdbId, mediaType, seasonNumber, episodeNumber, title, positionMs, durationMs, updatedAtMillis) " +
                "VALUES (1, 7, 'MOVIE', 0, 0, 'Seven', 10, 100, 1)",
        )

        migrate()

        assertEquals(listOf(listOf<Any?>(1, 7)), query("SELECT profileId, tmdbId FROM watch_progress"))
        assertEquals(listOf(listOf<Any?>(1, "Adult")), query("SELECT id, name FROM profiles"))
    }

    @Test
    fun `rows are keyed per profile and deleting a profile removes only its rows`() {
        insertProfile(1, "Adult")
        insertProfile(2, "Kid")
        migrate()

        insertLastUsed(profileId = 1, url = "https://a/1")
        insertLastUsed(profileId = 2, url = "https://a/2")
        assertEquals(
            listOf(listOf<Any?>(1, "https://a/1"), listOf<Any?>(2, "https://a/2")),
            query("SELECT profileId, streamUrl FROM last_used_streams ORDER BY profileId"),
        )

        exec("DELETE FROM profiles WHERE id = 2")
        assertEquals(listOf(listOf<Any?>(1)), query("SELECT profileId FROM last_used_streams"))
    }

    private fun migrate() = LastUsedStreamMigration.STATEMENTS.forEach(::exec)

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

    private fun insertProfile(id: Long, name: String) = exec(
        "INSERT INTO profiles (id, name, avatarIndex, isKids, movieCeiling, tvCeiling, allowUnrated, librarySortOrder, createdAtMillis) " +
            "VALUES ($id, '$name', 0, 0, NULL, NULL, 0, 'A_TO_Z', $id)",
    )

    private fun insertLastUsed(profileId: Long, url: String) = exec(
        "INSERT INTO last_used_streams (profileId, tmdbId, mediaType, seasonNumber, episodeNumber, addonUrl, streamUrl, label, description, updatedAtMillis) " +
            "VALUES ($profileId, 9, 'TV', 1, 2, 'https://addon', '$url', 'Addon · 4k', '', 1)",
    )

    /** Table and index DDL from Room's exported schema for [version]. */
    private fun schema(version: Int): List<String> {
        val file = File("schemas/com.seastreams.tv.core.database.SeaStreamsDatabase/$version.json")
        val entities = Json.parseToJsonElement(file.readText()).jsonObject.getValue("database").jsonObject.getValue("entities").jsonArray
        return entities.flatMap { element ->
            val entity = element.jsonObject
            val table = entity.getValue("tableName").jsonPrimitive.content
            val indices = entity["indices"]?.jsonArray.orEmpty().map { it.jsonObject.getValue("createSql").jsonPrimitive.content }
            (listOf(entity.getValue("createSql").jsonPrimitive.content) + indices).map { it.replace(TABLE_NAME, "`$table`") }
        }
    }

    private companion object {
        const val TABLE_NAME = "`\${TABLE_NAME}`"
    }
}
