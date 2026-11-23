package com.seastreams.tv.data.usenet

import com.seastreams.tv.core.datastore.UsenetSettingsStore
import com.seastreams.tv.data.addon.UsenetAddonState
import com.seastreams.tv.data.setup.SourceTogglesMigrator
import com.seastreams.tv.data.usenet.newznab.NzbRequest
import com.seastreams.tv.data.usenet.newznab.UsenetStreamSearch
import com.seastreams.tv.domain.model.StreamSource
import com.seastreams.tv.player.usenet.PreflightResult
import com.seastreams.tv.player.usenet.UsenetPreflight
import com.seastreams.tv.testutil.FakeNewznabIndexerDao
import com.seastreams.tv.testutil.FakeSecretCipher
import com.seastreams.tv.testutil.FakeUsenetProviderDao
import com.seastreams.tv.testutil.InMemoryPreferences
import com.seastreams.tv.usenet.nntp.NntpEndpoint
import com.seastreams.tv.usenet.session.UsenetEngineSetup
import com.seastreams.tv.usenet.session.UsenetGrab
import com.seastreams.tv.usenet.session.UsenetSessionRegistry
import com.seastreams.tv.usenet.session.UsenetStreamRegistry
import com.seastreams.tv.usenet.verify.ReleaseHealthCache
import dagger.Lazy
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock

/**
 * `Enable Usenet` (PRD §11, §12): OFF hides the built-in entry and stops searches and plays that need the user's
 * providers, without touching any provider, indexer or setting; the one-time upgrade turns it ON for set-up installs.
 */
class UsenetToggleGateTest {

    private val prefs = InMemoryPreferences()
    private val store = UsenetSettingsStore(prefs)
    private val providers = FakeUsenetProviderDao()
    private val indexers = FakeNewznabIndexerDao()
    private val repository = UsenetSourcesRepository(providers, indexers, FakeSecretCipher(), Dispatchers.Unconfined)

    private suspend fun configure() {
        repository.saveProvider(null, ProviderDraft("Eweka", "news.eweka.nl", username = "me", password = "pw"))
        repository.saveIndexer(null, IndexerDraft("NZBgeek", "https://api.nzbgeek.info", apiKey = "key"))
    }

    @Test
    fun `the built-in entry and the search follow the toggle, and switching keeps every row`() = runTest {
        configure()
        val state = UsenetAddonState(providers, indexers, store)
        val search = UsenetStreamSearch(repository, mockk(), UsenetStreamRegistry(), ReleaseHealthCache(), mockk<UsenetEngineSetup>(), Clock.systemUTC(), store)

        assertFalse(store.enabled.first(), "off by default")
        assertFalse(state.configured.first())
        assertFalse(search.configured())

        store.setEnabled(true)
        assertTrue(state.configured.first())
        assertTrue(search.configured())

        store.setEnabled(false)
        assertFalse(state.configured.first())
        assertEquals(1 to 1, providers.rows.value.size to indexers.rows.value.size)
        assertEquals(listOf("usenet/enabled"), prefs.keys.toList())
    }

    @Test
    fun `with the toggle OFF a saved Usenet pick is skipped unless the addon brings its own servers`() = runTest {
        val registry = UsenetStreamRegistry()
        val sessions = mockk<UsenetSessionRegistry>(relaxed = true)
        val preflight = UsenetPreflight(Lazy { sessions }, ReleaseHealthCache(), Dispatchers.Unconfined, store, registry)
        val nzb = NzbRequest("https://idx/get?id=1", "k1")
        val indexed = registry.register(UsenetGrab.Indexed(1, "Idx", "guid", nzb, "Movie", 1))
        val yours = registry.register(UsenetGrab.FromAddon("https://a/manifest.json", nzb.copy(url = "https://a/2"), "Movie", emptyList()))
        val own = registry.register(
            UsenetGrab.FromAddon(
                "https://a/manifest.json", nzb.copy(url = "https://a/3"), "Movie",
                listOf(NntpEndpoint(9, "addon", "news.example.com", 563, true, "", "", 4, 1, 0, false)),
            ),
        )
        fun stream(url: String) = StreamSource("https://a/manifest.json", "A", url, "A", "", emptyMap())

        assertEquals(PreflightResult.Skip("Usenet is switched off in Settings"), preflight.check(stream(indexed)))
        assertEquals(PreflightResult.Skip("Usenet is switched off in Settings"), preflight.check(stream(yours)))
        assertEquals(PreflightResult.Playable, preflight.check(stream(own)))
        assertEquals(PreflightResult.Playable, preflight.check(stream("https://cdn/direct.mkv")))
        verify(exactly = 1) { sessions.acquire(any()) }

        store.setEnabled(true)
        assertEquals(PreflightResult.Playable, preflight.check(stream(indexed)))
    }

    @Test
    fun `the upgrade turns Usenet on once for set-up installs and leaves fresh ones off`() = runTest {
        val migrator = SourceTogglesMigrator(providers, indexers, store)
        migrator.migrateIfNeeded()
        assertFalse(store.enabled.first(), "empty install stays off")
        assertTrue(store.toggleMigrated())

        // Providers added later never flip it on: the check ran once.
        configure()
        migrator.migrateIfNeeded()
        assertFalse(store.enabled.first())
    }

    @Test
    fun `an install upgraded with a provider and an indexer keeps its Usenet results`() = runTest {
        configure()
        SourceTogglesMigrator(providers, indexers, store).migrateIfNeeded()
        assertTrue(store.enabled.first())

        // A later choice is never overridden.
        store.setEnabled(false)
        SourceTogglesMigrator(providers, indexers, store).migrateIfNeeded()
        assertFalse(store.enabled.first())
    }

    @Test
    fun `providers without an indexer are not enough to turn it on`() = runTest {
        repository.saveProvider(null, ProviderDraft("Eweka", "news.eweka.nl"))
        SourceTogglesMigrator(providers, indexers, store).migrateIfNeeded()
        assertFalse(store.enabled.first())
    }
}
