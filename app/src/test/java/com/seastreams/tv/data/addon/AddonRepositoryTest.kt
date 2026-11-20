package com.seastreams.tv.data.addon

import com.seastreams.tv.data.UserFacingException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AddonOrderingTest {

    private val order = listOf("a", "b", "c", "d")

    @Test
    fun `move up and down by one`() {
        assertEquals(listOf("a", "c", "b", "d"), AddonOrdering.move(order, "c", -1))
        assertEquals(listOf("a", "c", "b", "d"), AddonOrdering.move(order, "b", 1))
    }

    @Test
    fun `move clamps at the ends and ignores unknown urls`() {
        assertEquals(order, AddonOrdering.move(order, "a", -1))
        assertEquals(order, AddonOrdering.move(order, "d", 5))
        assertEquals(listOf("b", "c", "d", "a"), AddonOrdering.move(order, "a", 99))
        assertEquals(order, AddonOrdering.move(order, "zzz", 1))
    }

    @Test
    fun `reconcile drops unknown and duplicate urls and appends omitted ones`() {
        assertEquals(listOf("d", "b", "a", "c"), AddonOrdering.reconcile(order, listOf("d", "x", "b", "d")))
        assertEquals(listOf("c", "b", "a", "d"), AddonOrdering.reconcile(order, listOf("c", "b", "a", "d")))
        assertEquals(order, AddonOrdering.reconcile(order, emptyList()))
    }
}

class AddonRepositoryTest : AddonRepositoryTestBase() {

    private val p = PROFILE_A

    @Test
    fun `seeding installs TMDB and OpenSubtitles enabled, and never overwrites`() = runTest {
        repository.seedDefaults(p)
        assertEquals(listOf(BuiltInAddons.TMDB_URL, BuiltInAddons.OPENSUBTITLES_URL), urls(p))
        assertTrue(dao.ordered(p).all { it.enabled && it.profileId == p })

        dao.rename(p, BuiltInAddons.OPENSUBTITLES_URL, "Subs")
        repository.seedDefaults(p)
        assertEquals("Subs", row(p, BuiltInAddons.OPENSUBTITLES_URL).customName)
    }

    @Test
    fun `install fetches the manifest and appends it to the end`() = runTest {
        repository.seedDefaults(p)
        val installed = repository.install(p, server.url("/good"))

        assertEquals(server.url("/good/manifest.json"), installed.manifestUrl)
        assertEquals("Good Addon", installed.name)
        assertEquals(p, installed.profileId)
        assertEquals(2, installed.sortOrder)
        assertEquals(clock.millis(), installed.installedAtMillis)
        val duplicate = assertThrows<UserFacingException> { repository.install(p, server.url("/good/manifest.json")) }
        assertEquals("That addon is already installed", duplicate.message)
    }

    @Test
    fun `install accepts a scheme-less LAN address over plain HTTP`() = runTest {
        val bareAddress = server.url("/good").removePrefix("http://")
        assertTrue(bareAddress.startsWith("127.0.0.1:"), bareAddress)

        val installed = repository.install(p, bareAddress)

        assertEquals(server.url("/good/manifest.json"), installed.manifestUrl)
        assertTrue(installed.manifestUrl.startsWith("http://"))
        assertEquals("Good Addon", installed.name)
        assertEquals(listOf(installed.manifestUrl), urls(p))
    }

    @Test
    fun `install reports invalid manifests and HTTP errors without saving`() = runTest {
        assertTrue(assertThrows<UserFacingException> { repository.install(p, server.url("/bad/manifest.json")) }
            .message.orEmpty().startsWith("Invalid addon manifest"))
        assertEquals("The addon server answered HTTP 404",
            assertThrows<UserFacingException> { repository.install(p, server.url("/gone/manifest.json")) }.message)
        assertThrows<UserFacingException> { repository.install(p, "ftp://nope") }
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test
    fun `operations on an unknown profile are refused`() = runTest {
        val missing = 99L
        assertEquals("Profile not found", assertThrows<UserFacingException> { repository.install(missing, server.url("/good")) }.message)
        assertThrows<UserFacingException> { repository.reorder(missing, emptyList()) }
        assertThrows<UserFacingException> { repository.move(missing, BuiltInAddons.TMDB_URL, 1) }
        assertTrue(dao.rows.value.isEmpty())
    }

    @Nested
    inner class Reordering {
        @BeforeEach
        fun seed() {
            dao.rows.value = listOf(addon(p, "a", 0), addon(p, "b", 1), addon(p, "c", 2), addon(p, "d", 3))
                .associateBy { it.profileId to it.manifestUrl }
        }

        @Test
        fun `move rewrites dense sort orders`() = runTest {
            repository.move(p, "c", -2)
            assertEquals(listOf("c", "a", "b", "d"), urls(p))
            assertEquals(listOf(0, 1, 2, 3), dao.ordered(p).map { it.sortOrder })
        }

        @Test
        fun `reorder applies a full client order`() = runTest {
            repository.reorder(p, listOf("d", "c", "b", "a"))
            assertEquals(listOf("d", "c", "b", "a"), urls(p))
        }

        @Test
        fun `remove closes the gap`() = runTest {
            repository.remove(p, "b")
            assertEquals(listOf("a" to 0, "c" to 1, "d" to 2), dao.ordered(p).map { it.manifestUrl to it.sortOrder })
        }

        @Test
        fun `moving an unknown addon fails`() = runTest {
            assertThrows<UserFacingException> { repository.move(p, "zzz", 1) }
        }
    }

    @Test
    fun `built-in TMDB cannot be removed or disabled but can be renamed`() = runTest {
        repository.seedDefaults(p)
        assertThrows<UserFacingException> { repository.remove(p, BuiltInAddons.TMDB_URL) }
        assertThrows<UserFacingException> { repository.setEnabled(p, BuiltInAddons.TMDB_URL, false) }
        repository.setEnabled(p, BuiltInAddons.OPENSUBTITLES_URL, false)
        repository.rename(p, BuiltInAddons.TMDB_URL, "  Metadata  ")

        assertEquals("Metadata", row(p, BuiltInAddons.TMDB_URL).customName)
        assertEquals(false, row(p, BuiltInAddons.OPENSUBTITLES_URL).enabled)
        repository.rename(p, BuiltInAddons.TMDB_URL, "")
        assertNull(row(p, BuiltInAddons.TMDB_URL).customName)
    }
}
