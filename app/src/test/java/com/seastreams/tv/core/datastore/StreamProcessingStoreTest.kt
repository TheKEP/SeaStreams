package com.seastreams.tv.core.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.seastreams.tv.domain.model.StreamSource
import com.seastreams.tv.domain.streams.AttributeFilter
import com.seastreams.tv.domain.streams.ContentKind
import com.seastreams.tv.domain.streams.FormatterId
import com.seastreams.tv.domain.streams.ProcessStreamsUseCase
import com.seastreams.tv.domain.streams.ResultLimits
import com.seastreams.tv.domain.streams.SortCriterion
import com.seastreams.tv.domain.streams.SortKey
import com.seastreams.tv.domain.streams.StreamContext
import com.seastreams.tv.domain.streams.StreamFormatterChoice
import com.seastreams.tv.domain.streams.StreamProcessingSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Clock

class StreamProcessingStoreTest {

    @TempDir
    lateinit var dir: File

    private lateinit var scope: CoroutineScope
    private val dataStore by lazy { PreferenceDataStoreFactory.create(scope = scope) { File(dir, "s.preferences_pb") } }
    private val store by lazy { StreamProcessingStore(dataStore) }

    @BeforeEach
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @AfterEach
    fun tearDown() = scope.cancel()

    @Test
    fun `nothing stored means no change`() = runTest {
        assertEquals(StreamProcessingSettings(), store.settings(1).first())
    }

    @Test
    fun `each profile keeps its own filters, sort and formatter`() = runTest {
        store.updateFilters(1) { it.copy(resolutions = AttributeFilter(excluded = listOf("480p"))) }
        store.updateSort(1) { it.copy(global = listOf(SortCriterion(SortKey.SIZE))) }
        store.updateFormatter(1) { StreamFormatterChoice(FormatterId.PRISM) }

        val one = store.settings(1).first()
        assertEquals(listOf("480p"), one.filters.resolutions.excluded)
        assertEquals(listOf(SortCriterion(SortKey.SIZE)), one.sort.global)
        assertEquals(FormatterId.PRISM, one.formatter.id)
        assertEquals(StreamProcessingSettings(), store.settings(2).first())

        val prefs = dataStore.data.first()
        assertEquals(setOf("p1/stream_filters", "p1/stream_sort", "p1/stream_formatter"), prefs.asMap().keys.map { it.name }.toSet())
    }

    @Test
    fun `one profile's filters never reach another profile's streams`() = runTest {
        store.updateFilters(1) { it.copy(resolutions = AttributeFilter(excluded = listOf("1080p"))) }
        val process = ProcessStreamsUseCase(store, Clock.systemUTC(), Dispatchers.Unconfined)
        val streams = listOf(source("Film.2020.1080p.WEB-GRP"), source("Film.2020.720p.WEB-GRP"))
        val context = StreamContext(ContentKind.MOVIE)

        assertEquals(1, process(1, streams, context).size)
        assertEquals(streams, process(2, streams, context))
    }

    @Test
    fun `defaults are removed, input is tidied and unreadable JSON reads as default`() = runTest {
        store.updateFilters(1) { it.copy(resolutions = AttributeFilter(excluded = listOf(" 480p ", "480p", "")), limits = ResultLimits(global = 0, perAddon = 3)) }
        val filters = store.settings(1).first().filters
        assertEquals(listOf("480p"), filters.resolutions.excluded)
        assertEquals(ResultLimits(perAddon = 3), filters.limits)

        store.updateFilters(1) { com.seastreams.tv.domain.streams.StreamFilters() }
        assertNull(dataStore.data.first()[stringPreferencesKey("p1/stream_filters")])

        dataStore.edit { it[stringPreferencesKey("p1/stream_sort")] = "{not json" }
        assertEquals(StreamProcessingSettings(), store.settings(1).first())
    }

    @Test
    fun `deleting a profile clears its stream settings`() = runTest {
        store.updateFormatter(3) { StreamFormatterChoice(FormatterId.CUSTOM, "{addon.name}", null) }
        SettingsDataStore(dataStore).clearProfile(3)
        assertEquals(StreamProcessingSettings(), store.settings(3).first())
    }

    @Test
    fun `stored JSON from before the master toggles reads as every stage on`() = runTest {
        dataStore.edit {
            it[stringPreferencesKey("p1/stream_sort")] = """{"global":[{"key":"SIZE"}]}"""
            it[stringPreferencesKey("p1/stream_formatter")] = """{"id":"PRISM"}"""
        }
        val settings = store.settings(1).first()
        assertEquals(listOf(SortCriterion(SortKey.SIZE)), settings.sort.global)
        assertEquals(FormatterId.PRISM, settings.formatter.id)
        assertTrue(settings.filteringEnabled && settings.sortingEnabled && settings.formattingEnabled)
    }

    @Test
    fun `switching a stage off keeps its rules, and later edits keep the switch`() = runTest {
        store.updateSort(1) { it.copy(global = listOf(SortCriterion(SortKey.SIZE))) }
        store.setSortingEnabled(1, false)

        val off = store.settings(1).first()
        assertFalse(off.sortingEnabled)
        assertEquals(listOf(SortCriterion(SortKey.SIZE)), off.sort.global)
        assertTrue(off.filteringEnabled && off.formattingEnabled)
        val raw = dataStore.data.first()[stringPreferencesKey("p1/stream_sort")].orEmpty()
        assertTrue(raw.contains(""""enabled":false"""), raw)

        store.updateSort(1) { it.copy(global = listOf(SortCriterion(SortKey.SIZE), SortCriterion(SortKey.SEEDERS))) }
        assertFalse(store.settings(1).first().sortingEnabled)

        store.setSortingEnabled(1, true)
        val on = store.settings(1).first()
        assertTrue(on.sortingEnabled)
        assertEquals(listOf(SortKey.SIZE, SortKey.SEEDERS), on.sort.global.map { it.key })
        assertFalse(dataStore.data.first()[stringPreferencesKey("p1/stream_sort")].orEmpty().contains("enabled"))
    }

    @Test
    fun `an off switch is kept even with default rules, and on again removes the key`() = runTest {
        store.setFormattingEnabled(1, false)
        assertEquals("""{"enabled":false}""", dataStore.data.first()[stringPreferencesKey("p1/stream_formatter")])
        assertFalse(store.settings(1).first().formattingEnabled)

        store.setFormattingEnabled(1, true)
        assertNull(dataStore.data.first()[stringPreferencesKey("p1/stream_formatter")])
        assertEquals(StreamProcessingSettings(), store.settings(1).first())
    }

    @Test
    fun `each profile has its own switches`() = runTest {
        store.updateFilters(1) { it.copy(resolutions = AttributeFilter(excluded = listOf("480p"))) }
        store.updateFilters(2) { it.copy(resolutions = AttributeFilter(excluded = listOf("480p"))) }
        store.setFilteringEnabled(1, false)
        store.setSortingEnabled(2, false)

        val one = store.settings(1).first()
        val two = store.settings(2).first()
        assertFalse(one.filteringEnabled)
        assertTrue(one.sortingEnabled)
        assertTrue(two.filteringEnabled)
        assertFalse(two.sortingEnabled)
        assertEquals(one.filters, two.filters)
    }

    @Test
    fun `filtering off bypasses one profile's filters until it is switched back on`() = runTest {
        store.updateFilters(1) { it.copy(resolutions = AttributeFilter(excluded = listOf("1080p"))) }
        store.updateFilters(2) { it.copy(resolutions = AttributeFilter(excluded = listOf("1080p"))) }
        val process = ProcessStreamsUseCase(store, Clock.systemUTC(), Dispatchers.Unconfined)
        val streams = listOf(source("Film.2020.1080p.WEB-GRP"), source("Film.2020.720p.WEB-GRP"))
        val context = StreamContext(ContentKind.MOVIE)

        store.setFilteringEnabled(1, false)
        assertSame(streams, process(1, streams, context))
        assertEquals(1, process(2, streams, context).size)

        store.setFilteringEnabled(1, true)
        assertEquals(listOf(streams[1].url), process(1, streams, context).map { it.url })
    }

    private fun source(name: String) = StreamSource("https://a/manifest.json", "a", "https://cdn/$name", "a", name, emptyMap())
}
