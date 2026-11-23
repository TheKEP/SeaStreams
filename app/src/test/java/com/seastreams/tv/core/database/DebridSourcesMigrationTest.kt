package com.seastreams.tv.core.database

import com.seastreams.tv.testutil.exportedSchemaSql
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/**
 * Runs [DebridSourcesMigration]'s real SQL against an in-memory SQLite database built from the exported v7 schema and
 * checks the result against the exported v8 schema.
 */
class DebridSourcesMigrationTest {

    private lateinit var db: Connection

    @BeforeEach
    fun setUp() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        exec("PRAGMA foreign_keys = ON")
        exportedSchemaSql(7).forEach(::exec)
    }

    @AfterEach
    fun tearDown() = db.close()

    @Test
    fun `the migration SQL is Room's exported v8 createSql verbatim`() {
        assertEquals(exportedSchemaSql(8).filter { it.contains("`debrid_providers`") }, DebridSourcesMigration.STATEMENTS)
        assertEquals(7, DebridSourcesMigration.startVersion)
        assertEquals(8, DebridSourcesMigration.endVersion)
    }

    @Test
    fun `the migrated table matches the Room v8 schema, app-wide, unique per service and indexed by order`() {
        migrate()
        exec(exportedSchemaSql(8).first { it.contains("TABLE IF NOT EXISTS `debrid_providers`") }.replace("`debrid_providers`", "`expected`"))

        assertEquals(query("PRAGMA table_info(`expected`)"), query("PRAGMA table_info(`debrid_providers`)"))
        assertEquals(emptyList<List<Any?>>(), query("PRAGMA foreign_key_list(`debrid_providers`)"))
        assertEquals(listOf("providerType"), query("PRAGMA index_info(`index_debrid_providers_providerType`)").map { it[2] })
        assertEquals(listOf("sortOrder"), query("PRAGMA index_info(`index_debrid_providers_sortOrder`)").map { it[2] })
        val unique = query("PRAGMA index_list(`debrid_providers`)").first { it[1] == "index_debrid_providers_providerType" }[2]
        assertEquals(1, unique)
    }

    @Test
    fun `v7 Usenet providers, indexers and profiles survive and Debrid starts empty`() {
        exec(
            "INSERT INTO profiles (id, name, avatarIndex, isKids, movieCeiling, tvCeiling, allowUnrated, librarySortOrder, createdAtMillis) " +
                "VALUES (1, 'Adult', 0, 0, NULL, NULL, 0, 'A_TO_Z', 1)",
        )
        exec(
            "INSERT INTO usenet_providers (name, host, port, tls, username, passwordCipher, maxConnections, pipelineDepth, priority, isBackup, enabled, sortOrder) " +
                "VALUES ('Eweka', 'news.eweka.nl', 563, 1, 'me', 'v1:pw', 50, 2, 0, 0, 1, 0)",
        )
        exec(
            "INSERT INTO newznab_indexers (name, baseUrl, apiPath, apiKeyCipher, priority, backupOnly, enabled, sortOrder) " +
                "VALUES ('NZBgeek', 'https://api.nzbgeek.info', '/api', 'v1:key', 0, 0, 1, 0)",
        )

        migrate()

        assertEquals(listOf(listOf<Any?>(1, "Adult")), query("SELECT id, name FROM profiles"))
        assertEquals(listOf(listOf<Any?>("Eweka", "v1:pw")), query("SELECT name, passwordCipher FROM usenet_providers"))
        assertEquals(listOf(listOf<Any?>("NZBgeek", "v1:key")), query("SELECT name, apiKeyCipher FROM newznab_indexers"))
        assertEquals(listOf(listOf<Any?>(0)), query("SELECT COUNT(*) FROM debrid_providers"))
    }

    @Test
    fun `one account per service, nullable refresh data, autoincrement ids`() {
        migrate()
        insert("REAL_DEBRID", "'v1:token'", "'v1:refresh'", 1_800_000_000_000)
        insert("TORBOX", "'v1:key'", "NULL", null)

        assertEquals(
            listOf(listOf<Any?>(1, "REAL_DEBRID", "v1:refresh"), listOf<Any?>(2, "TORBOX", null)),
            query("SELECT id, providerType, refreshTokenCipher FROM debrid_providers ORDER BY id"),
        )
        val duplicate = assertThrows<SQLException> { insert("TORBOX", "'v1:other'", "NULL", null) }
        assertTrue(duplicate.message.orEmpty().contains("UNIQUE"), duplicate.message)
    }

    private fun insert(type: String, credential: String, refresh: String, expiresAt: Long?) = exec(
        "INSERT INTO debrid_providers (providerType, name, authMethod, credentialCipher, refreshTokenCipher, clientIdCipher, " +
            "clientSecretCipher, expiresAtMillis, signInNeeded, enabled, sortOrder) " +
            "VALUES ('$type', '$type', 'API_KEY', $credential, $refresh, NULL, NULL, ${expiresAt ?: "NULL"}, 0, 1, 0)",
    )

    private fun migrate() = DebridSourcesMigration.STATEMENTS.forEach(::exec)

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
