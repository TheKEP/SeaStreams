package com.seastreams.tv.testutil

import com.seastreams.tv.data.usenet.newznab.UsenetStreamSearch
import com.seastreams.tv.player.usenet.UsenetPreflight
import com.seastreams.tv.usenet.verify.ReleaseHealthCache
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers

/** The built-in Usenet search with no provider or indexer enabled: no `Usenet` card, never searched. */
fun unconfiguredUsenet(): Lazy<UsenetStreamSearch> {
    val search = mockk<UsenetStreamSearch> { coEvery { configured() } returns false }
    return Lazy { search }
}

/** A [UsenetPreflight] for tests without Usenet streams: other streams pass without touching the session graph. */
fun noUsenetPreflight(): UsenetPreflight =
    UsenetPreflight(Lazy { error("No Usenet session expected in this test") }, ReleaseHealthCache(), Dispatchers.Unconfined)
