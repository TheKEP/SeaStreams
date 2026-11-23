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
import org.junit.jupiter.api.Assertions.assertNull
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

    private fun source(name: String) = StreamSource("https://a/manifest.json", "a", "https://cdn/$name", "a", name, emptyMap())
}
