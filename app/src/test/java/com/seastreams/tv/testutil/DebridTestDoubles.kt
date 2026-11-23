package com.seastreams.tv.testutil

import com.seastreams.tv.data.addon.DebridAddonState
import com.seastreams.tv.data.debrid.library.DebridLibrarySearch
import com.seastreams.tv.data.debrid.stream.DebridLinkResolver
import com.seastreams.tv.player.StreamPreflight
import com.seastreams.tv.player.debrid.DebridPreflight
import com.seastreams.tv.player.usenet.UsenetPreflight
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** The built-in Debrid search with Debrid off: no `Debrid` card, never searched. */
fun unconfiguredDebrid(): Lazy<DebridLibrarySearch> {
    val search = mockk<DebridLibrarySearch> { coEvery { configured() } returns false }
    return Lazy { search }
}

/** A [DebridAddonState] driven by [listed] (default: Debrid not set up). */
fun debridAddonState(listed: Flow<Boolean> = flowOf(false)): DebridAddonState = mockk {
    every { this@mockk.listed } returns listed
}

/** A [DebridPreflight] for tests without Debrid streams: resolving anything fails the test. */
fun noDebridPreflight(): DebridPreflight =
    DebridPreflight(Lazy<DebridLinkResolver> { error("No Debrid link expected in this test") })

/** The pre-player check for tests: Usenet and Debrid parts replaceable, neither expected by default. */
fun streamPreflight(usenet: UsenetPreflight = noUsenetPreflight(), debrid: DebridPreflight = noDebridPreflight()): StreamPreflight =
    StreamPreflight(usenet, debrid)

/** A [StreamPreflight] for tests with only Stremio streams. */
fun noStreamPreflight(): StreamPreflight = streamPreflight()
