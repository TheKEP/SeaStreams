package com.seastreams.tv.ui.screens.settings

import com.seastreams.tv.core.database.entity.AddonEntity
import com.seastreams.tv.data.addon.BuiltInAddons
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The built-in `Usenet` entry in Manage addons and the Preferred auto-play addon choice. */
class UsenetAddonRowTest {

    private val stremio = AddonEntity(
        1, "https://torrentio/manifest.json", "t", "Torrentio", null, true, 0,
        """{"id":"t","version":"1","name":"Torrentio","types":["movie"],"resources":["stream"]}""", 0,
    )

    @Test
    fun `Usenet can be the preferred auto-play addon, in the profile's order`() {
        val usenet = BuiltInAddons.usenet(1, sortOrder = 0, nowMillis = 0)
        val tmdb = BuiltInAddons.tmdb(1, sortOrder = 2, nowMillis = 0)
        assertEquals(listOf(BuiltInAddons.USENET_URL, stremio.manifestUrl), streamAddons(listOf(usenet, stremio, tmdb)).map { it.manifestUrl })
        assertEquals(listOf(stremio.manifestUrl), streamAddons(listOf(usenet.copy(enabled = false), stremio)).map { it.manifestUrl })
    }

    @Test
    fun `state labels for built-ins`() {
        assertEquals("Built-in", addonStateLabel(builtIn = true, canDisable = false, enabled = true))
        assertEquals("Built-in · Disabled", addonStateLabel(builtIn = true, canDisable = true, enabled = false))
        assertEquals("Enabled", addonStateLabel(builtIn = false, canDisable = true, enabled = true))
        assertEquals(false, BuiltInAddons.isListed(BuiltInAddons.USENET_URL, usenetConfigured = false, debridListed = false))
        assertEquals(true, BuiltInAddons.isListed(stremio.manifestUrl, usenetConfigured = false, debridListed = false))
    }
}
