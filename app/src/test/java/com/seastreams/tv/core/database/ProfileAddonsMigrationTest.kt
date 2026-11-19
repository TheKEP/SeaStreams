package com.seastreams.tv.core.database

import com.seastreams.tv.testutil.exportedSchemaSql
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * (c) Runs [ProfileAddonsMigration]'s real SQL against an in-memory SQLite database built from the
 * exported v4 schema, and checks the result against the exported v5 schema.
 */
class ProfileAddonsMigrationTest {

    private lateinit var db: Connection

    @BeforeEach
    fun setUp() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        exec("PRAGMA foreign_keys = ON")
        exportedSchemaSql(4).forEach(::exec)
    }

    @AfterEach
    fun tearDown() = db.close()

    @Test
    fun `every existing profile receives a full copy of every legacy addon`() {
        insertProfile(1, "Adult")
        insertProfile(2, "Kid")
        insertLegacyAddon("builtin://tmdb", "TMDB Metadata", customName = "Meta", enabled = true, sortOrder = 0)
        insertLegacyAddon("https://subs/manifest.json", "Subs", customName = null, enabled = false, sortOrder = 2)
        insertLegacyAddon("https://streams/manifest.json", "Streams", customName = null, enabled = true, sortOrder = 1)
        val legacy = query("SELECT manifestUrl, manifestId, name, customName, enabled, sortOrder, manifestJson, installedAtMillis FROM addons ORDER BY sortOrder")

        migrate()

        assertEquals(6, query("SELECT COUNT(*) FROM addons").single().single())
        listOf(1L, 2L).forEach { profileId ->
            val copied = query(
                "SELECT manifestUrl, manifestId, name, customName, enabled, sortOrder, manifestJson, installedAtMillis " +
                    "FROM addons WHERE profileId = $profileId ORDER BY sortOrder",
            )
            assertEquals(legacy, copied)
        }
    }

    @Test
    fun `migrated table matches the Room v5 schema and cascades on profile deletion`() {
        insertProfile(1, "Adult")
        insertProfile(2, "Kid")
        insertLegacyAddon("builtin://tmdb", "TMDB Metadata", customName = null, enabled = true, sortOrder = 0)

        migrate()

        // Room validates columns, keys and foreign keys on open; compare them with a table built from v5's createSql.
        exec(exportedSchemaSql(5).first { it.contains("TABLE IF NOT EXISTS `addons`") }.replace("`addons`", "`expected`"))
        assertEquals(query("PRAGMA table_info(`expected`)"), query("PRAGMA table_info(`addons`)"))
        assertEquals(query("PRAGMA foreign_key_list(`expected`)"), query("PRAGMA foreign_key_list(`addons`)"))
        val indices = query("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'addons' AND sql IS NOT NULL ORDER BY name")
        assertEquals(listOf(listOf("index_addons_profileId"), listOf("index_addons_profileId_sortOrder")), indices)

        exec("DELETE FROM profiles WHERE id = 2")
        assertEquals(listOf(listOf<Any?>(1)), query("SELECT profileId FROM addons"))
    }

    @Test
    fun `with no profiles the legacy rows are dropped`() {
        insertLegacyAddon("builtin://tmdb", "TMDB Metadata", customName = null, enabled = true, sortOrder = 0)

        migrate()

        assertTrue(query("SELECT * FROM addons").isEmpty())
    }

    private fun migrate() = ProfileAddonsMigration.STATEMENTS.forEach(::exec)

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

    private fun insertLegacyAddon(url: String, name: String, customName: String?, enabled: Boolean, sortOrder: Int) = exec(
        "INSERT INTO addons (manifestUrl, manifestId, name, customName, enabled, sortOrder, manifestJson, installedAtMillis) " +
            "VALUES ('$url', 'id.$name', '$name', ${customName?.let { "'$it'" } ?: "NULL"}, ${if (enabled) 1 else 0}, " +
            "$sortOrder, '{}', ${1000 + sortOrder})",
    )
}
