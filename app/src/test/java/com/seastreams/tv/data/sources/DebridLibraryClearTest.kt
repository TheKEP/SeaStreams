package com.seastreams.tv.data.sources

import com.seastreams.tv.data.debrid.DebridServiceKind
import com.seastreams.tv.data.debrid.auth.DeviceAuthSessions
import com.seastreams.tv.data.debrid.auth.DeviceFlowProvider
import com.seastreams.tv.data.debrid.stream.DebridStreamRegistry
import com.seastreams.tv.data.usenet.newznab.UsenetSearchTarget
import com.seastreams.tv.player.debrid.DebridPreflight
import com.seastreams.tv.player.usenet.PreflightResult
import com.seastreams.tv.testutil.DebridLibraryRig
import com.seastreams.tv.testutil.FakeLibraryClient
import com.seastreams.tv.testutil.VirtualClock
import com.seastreams.tv.testutil.debridItem
import dagger.Lazy
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `Clear Debrid settings` (PRD §11, §14) also empties the library cache, the playback links and the registered streams. */
class DebridLibraryClearTest {

    @Test
    fun `after the Undo window nothing read from the cleared accounts survives`() = runTest {
        val clock = VirtualClock(testScheduler)
        val rig = DebridLibraryRig(clock)
        val rd = FakeLibraryClient(
            DebridServiceKind.REAL_DEBRID,
            listOf(debridItem("t:1", "Inception.2010.1080p.mkv")),
            links = mutableMapOf("t:1-file" to "https://dl.real-debrid.com/1.mkv"),
        )
        rig.add(rd)
        rig.enable()
        val stream = rig.search.search(UsenetSearchTarget.Movie(null, "Inception", 2010)).streams.single()
        val token = checkNotNull(DebridStreamRegistry.tokenOf(stream.url))
        assertEquals(PreflightResult.Playable, DebridPreflight(Lazy { rig.resolver }).check(stream))
        assertNotNull(rig.registry.get(token))

        val sessions = DeviceAuthSessions(DeviceFlowProvider { null }, rig.repository, backgroundScope, clock)
        val clear = DebridSettingsClear(rig.repository, rig.settings, sessions, rig.cache, rig.resolver, rig.registry, backgroundScope, clock)
        clear.schedule()
        advanceTimeBy(PendingSourceClear.UNDO_WINDOW_MILLIS + 1)
        runCurrent()

        assertTrue(rig.dao.rows.value.isEmpty())
        assertNull(rig.registry.get(token), "registered streams (and their service links) are gone")
        // Re-adding the same account lists again (the cache was emptied) and resolves a fresh link (the links were too).
        rig.add(rd)
        rig.enable()
        val again = rig.search.search(UsenetSearchTarget.Movie(null, "Inception", 2010)).streams.single()
        assertEquals(stream.url, again.url)
        assertEquals(2, rd.libraryCalls)
        assertEquals(PreflightResult.Playable, DebridPreflight(Lazy { rig.resolver }).check(again))
        assertEquals(2, rd.resolveCalls)
    }
}
