package com.seastreams.tv.core.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.seastreams.tv.domain.streams.AttributeFilter
import com.seastreams.tv.domain.streams.FormatterId
import com.seastreams.tv.domain.streams.MovieSeriesRange
import com.seastreams.tv.domain.streams.NamedRegex
import com.seastreams.tv.domain.streams.RangeRule
import com.seastreams.tv.domain.streams.ResultLimits
import com.seastreams.tv.domain.streams.SizeLimits
import com.seastreams.tv.domain.streams.SortCriterion
import com.seastreams.tv.domain.streams.SortDirection
import com.seastreams.tv.domain.streams.SortKey
import com.seastreams.tv.domain.streams.StreamFormatterChoice
import com.seastreams.tv.domain.streams.StreamProcessingSettings
import com.seastreams.tv.domain.streams.ValueRange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** `Copy stream settings from…` and the JSON backup (PRD §13 *Backup & copy*). */
class StreamProcessingBackupTest {

    @TempDir
    lateinit var dir: File

    private lateinit var scope: CoroutineScope
    private val store by lazy { StreamProcessingStore(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "s.preferences_pb") }) }

    @BeforeEach
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @AfterEach
    fun tearDown() = scope.cancel()

    /** Rules in every stage, filtering and formatting switched off. */
    private suspend fun seed(profileId: Long) {
        store.updateFilters(profileId, enabled = false) {
            it.copy(
                resolutions = AttributeFilter(excluded = listOf("480p")),
                regex = it.regex.copy(preferred = listOf(NamedRegex("/remux/i", "Remux"))),
                seeders = RangeRule(required = ValueRange(min = 5)),
            )
        }
        store.updateSort(profileId) { it.copy(global = listOf(SortCriterion(SortKey.SIZE, SortDirection.ASC))) }
        store.updateFormatter(profileId, enabled = false) { StreamFormatterChoice(FormatterId.CUSTOM, "{addon.name}  {stream.resolution}", null) }
    }

    @Test
    fun `copyProfile copies every stage and toggle onto the target only`() = runTest {
        seed(1)
        store.updateSort(3) { it.copy(global = listOf(SortCriterion(SortKey.SEEDERS))) }
        val source = store.settings(1).first()

        store.copyProfile(fromProfileId = 1, toProfileId = 2)

        assertEquals(source, store.settings(2).first())
        assertFalse(store.settings(2).first().filteringEnabled)
        assertTrue(store.settings(2).first().sortingEnabled)
        assertFalse(store.settings(2).first().formattingEnabled)
        assertEquals(source, store.settings(1).first())
        assertEquals(listOf(SortCriterion(SortKey.SEEDERS)), store.settings(3).first().sort.global)
        assertEquals(StreamFormatterChoice(), store.settings(3).first().formatter)
    }

    @Test
    fun `a copy is independent of its source afterwards`() = runTest {
        seed(1)
        store.copyProfile(1, 2)
        store.updateFilters(2, enabled = true) { it.copy(resolutions = AttributeFilter()) }
        store.setFormattingEnabled(1, true)

        assertEquals(listOf("480p"), store.settings(1).first().filters.resolutions.excluded)
        assertFalse(store.settings(1).first().filteringEnabled)
        assertFalse(store.settings(2).first().formattingEnabled)
    }

    @Test
    fun `copying from a profile with nothing stored resets the target, and copying onto itself does nothing`() = runTest {
        seed(2)
        store.copyProfile(1, 1)
        assertEquals(StreamProcessingSettings(), store.settings(1).first())
        store.copyProfile(fromProfileId = 1, toProfileId = 2)
        assertEquals(StreamProcessingSettings(), store.settings(2).first())
    }

    @Test
    fun `export then import round-trips rules and master toggles through JSON`() = runTest {
        seed(1)
        val json = StreamProcessingBackupCodec.encode(store.exportBackup(1))
        assertTrue(json.contains("\"version\": 1"), json)

        store.importBackup(2, StreamProcessingBackupCodec.decode(json))

        assertEquals(store.settings(1).first(), store.settings(2).first())
        assertEquals("{addon.name}  {stream.resolution}", store.settings(2).first().formatter.customName)
    }

    @Test
    fun `importing replaces all three stages, including turning a toggle back on`() = runTest {
        seed(1)
        store.importBackup(1, StreamProcessingBackup.of(StreamProcessingSettings()))
        assertEquals(StreamProcessingSettings(), store.settings(1).first())
    }

    @Test
    fun `import clamps out-of-range numbers instead of refusing them`() {
        val filters = StreamProcessingSettings().filters.copy(
            seeders = RangeRule(required = ValueRange(min = -5, max = Long.MAX_VALUE)),
            ageHours = RangeRule(excluded = ValueRange(min = 500, max = 100)),
            size = SizeLimits(byResolution = mapOf("2160p" to MovieSeriesRange(movies = ValueRange(max = Long.MAX_VALUE)))),
            limits = ResultLimits(global = -1, perAddon = 1_000_000),
        )
        val json = StreamProcessingBackupCodec.encode(StreamProcessingBackup.of(StreamProcessingSettings(filters = filters)))

        val decoded = StreamProcessingBackupCodec.decode(json).filters.rules
        assertEquals(ValueRange(0, StreamProcessingBackupCodec.MAX_SEEDERS), decoded.seeders.required)
        assertEquals(ValueRange(100, 500), decoded.ageHours.excluded)
        assertEquals(StreamProcessingBackupCodec.MAX_SIZE_BYTES, decoded.size.byResolution.getValue("2160p").movies.max)
        assertEquals(ResultLimits(global = null, perAddon = StreamProcessingBackupCodec.MAX_RESULTS), decoded.limits)
    }

    @Test
    fun `missing toggles read as on, and unknown fields are ignored`() {
        val json = """{"version":1,"extra":true,"filters":{"rules":{}},"sort":{"enabled":false,"rules":{}},"formatter":{"rules":{"id":"PRISM"}}}"""
        val settings = StreamProcessingBackupCodec.decode(json).toSettings()
        assertTrue(settings.filteringEnabled && settings.formattingEnabled)
        assertFalse(settings.sortingEnabled)
        assertEquals(FormatterId.PRISM, settings.formatter.id)
    }

    @Test
    fun `invalid JSON, another version, an unknown formatter or an invalid regex is refused`() {
        fun refused(json: String): String = assertThrows(InvalidStreamBackupException::class.java) { StreamProcessingBackupCodec.decode(json) }.message.orEmpty()
        val stages = """"filters":{"rules":{}},"sort":{"rules":{}},"formatter":{"rules":{}}"""

        assertEquals("That isn't a seastreams stream settings file", refused("not json"))
        assertEquals("That isn't a seastreams stream settings file", refused("""{"version":1}"""))
        assertEquals("That isn't a seastreams stream settings file", refused("""{"version":1,"filters":{"rules":{}},"sort":{"rules":{}},"formatter":{"rules":{"id":"NOPE"}}}"""))
        assertEquals("Unsupported stream settings file version 2", refused("""{"version":2,$stages}"""))
        assertEquals(
            "Not a valid regex: /([oops/",
            refused("""{"version":1,"filters":{"rules":{"regex":{"excluded":["/([oops/"]}}},"sort":{"rules":{}},"formatter":{"rules":{}}}"""),
        )
        val long = "x".repeat(StreamProcessingStore.MAX_TEMPLATE_CHARS + 1)
        assertTrue(refused("""{"version":1,"filters":{"rules":{}},"sort":{"rules":{}},"formatter":{"rules":{"id":"CUSTOM","customName":"$long"}}}""").startsWith("Templates are limited"))
    }
}
