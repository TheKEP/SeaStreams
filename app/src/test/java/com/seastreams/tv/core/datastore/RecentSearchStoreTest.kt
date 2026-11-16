package com.seastreams.tv.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.seastreams.tv.domain.model.StreamingProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Recent searches and Movies/TV provider toggles, both stored in the shared Preferences DataStore. */
class RecentSearchStoreTest {

    @TempDir
    lateinit var dir: File

    private lateinit var scope: CoroutineScope
    private lateinit var store: DataStore<Preferences>
    private lateinit var recents: RecentSearchStore

    @BeforeEach
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        store = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "test.preferences_pb") }
        recents = RecentSearchStore(store)
    }

    @AfterEach
    fun tearDown() = scope.cancel()

    @Test
    fun `newest search comes first and repeats move to the front case-insensitively`() = runTest {
        recents.add(1, "The Matrix")
        recents.add(1, "  alien   romulus ")
        recents.add(1, "the matrix")

        assertEquals(listOf("the matrix", "alien romulus"), recents.observe(1).first())
    }

    @Test
    fun `history is per profile, capped, ignores blanks and clears`() = runTest {
        (1..10).forEach { recents.add(1, "query $it") }
        recents.add(1, "   ")
        recents.add(2, "kids show")

        assertEquals(RecentSearchStore.MAX_RECENT, recents.observe(1).first().size)
        assertEquals("query 10", recents.observe(1).first().first())
        assertEquals(listOf("kids show"), recents.observe(2).first())

        recents.clear(1)

        assertEquals(emptyList<String>(), recents.observe(1).first())
        assertEquals(listOf("kids show"), recents.observe(2).first())
    }

    @Test
    fun `every streaming provider starts enabled and toggles persist in order`() = runTest {
        val settings = SettingsDataStore(store)
        assertEquals(StreamingProvider.entries, settings.enabledProviders.first())

        settings.setProviderEnabled(StreamingProvider.HULU, enabled = false)
        settings.setProviderEnabled(StreamingProvider.NETFLIX, enabled = false)
        settings.setProviderEnabled(StreamingProvider.NETFLIX, enabled = true)

        assertEquals(StreamingProvider.entries - StreamingProvider.HULU, settings.enabledProviders.first())
    }
}
