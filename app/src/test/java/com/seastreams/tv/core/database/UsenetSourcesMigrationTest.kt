package com.seastreams.tv.core.database

import com.seastreams.tv.testutil.exportedSchemaSql
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * Runs [UsenetSourcesMigration]'s real SQL against an in-memory SQLite database built from the
 * exported v6 schema and checks the result against the exported v7 schema.
 */
class UsenetSourcesMigrationTest {

    private lateinit var db: Connection

    @BeforeEach
    fun setUp() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        exec("PRAGMA foreign_keys = ON")
        exportedSchemaSql(6).forEach(::exec)
    }

    @AfterEach
    fun tearDown() = db.close()

    @Test
    fun `the migration SQL is Room's exported v7 createSql verbatim`() {
        val exported = exportedSchemaSql(7).filter { it.contains("`usenet_providers`") || it.contains("`newznab_indexers`") }
        assertEquals(exported, UsenetSourcesMigration.STATEMENTS)
    }

    @Test
    fun `migrated tables match the Room v7 schema, with no profile foreign key`() {
        migrate()

        listOf("usenet_providers", "newznab_indexers").forEach { table ->
            exec(exportedSchemaSql(7).first { it.contains("TABLE IF NOT EXISTS `$table`") }.replace("`$table`", "`expected_$table`"))
            assertEquals(query("PRAGMA table_info(`expected_$table`)"), query("PRAGMA table_info(`$table`)"), table)
            assertEquals(emptyList<List<Any?>>(), query("PRAGMA foreign_key_list(`$table`)"), "$table is app-wide")
            assertEquals(listOf("sortOrder"), query("PRAGMA index_info(`index_${table}_sortOrder`)").map { it[2] })
        }
    }

    @Test
    fun `existing v6 data survives and the new tables start empty`() {
        exec(
            "INSERT INTO profiles (id, name, avatarIndex, isKids, movieCeiling, tvCeiling, allowUnrated, librarySortOrder, createdAtMillis) " +
                "VALUES (1, 'Adult', 0, 0, NULL, NULL, 0, 'A_TO_Z', 1)",
        )

        migrate()

        assertEquals(listOf(listOf<Any?>(1, "Adult")), query("SELECT id, name FROM profiles"))
        assertEquals(listOf(listOf<Any?>(0)), query("SELECT COUNT(*) FROM usenet_providers"))
        assertEquals(listOf(listOf<Any?>(0)), query("SELECT COUNT(*) FROM newznab_indexers"))
    }

    @Test
    fun `providers keep a nullable encrypted password and autoincrement ids`() {
        migrate()
        exec(
            "INSERT INTO usenet_providers (name, host, port, tls, username, passwordCipher, maxConnections, pipelineDepth, priority, isBackup, enabled, sortOrder) " +
                "VALUES ('Eweka', 'news.eweka.nl', 563, 1, 'me', NULL, 50, 2, 0, 0, 1, 0)",
        )
        exec(
            "INSERT INTO newznab_indexers (name, baseUrl, apiPath, apiKeyCipher, priority, backupOnly, enabled, sortOrder) " +
                "VALUES ('NZBgeek', 'https://api.nzbgeek.info', '/api', 'v1:abc', 0, 0, 1, 0)",
        )

        assertEquals(listOf(listOf<Any?>(1, null)), query("SELECT id, passwordCipher FROM usenet_providers"))
        assertEquals(listOf(listOf<Any?>(1, "v1:abc")), query("SELECT id, apiKeyCipher FROM newznab_indexers"))
    }

    private fun migrate() = UsenetSourcesMigration.STATEMENTS.forEach(::exec)

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
