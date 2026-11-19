package com.seastreams.tv.ui.screens.playback

import com.seastreams.tv.domain.model.StreamAddon
import com.seastreams.tv.domain.model.StreamSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** PRD §6 Addon Selector Cards: grouping by addon and where the panel opens. */
class AddonStreamGroupsTest {

    private val alpha = StreamAddon("https://alpha", "Alpha")
    private val beta = StreamAddon("https://beta", "Beta")
    private val empty = StreamAddon("https://empty", "Empty")

    // The flat, addon-ordered list auto-play and fallback use: Alpha 0..1, Beta 2..4.
    private val streams = listOf(stream(alpha, 1), stream(alpha, 2), stream(beta, 1), stream(beta, 2), stream(beta, 3))

    @Test
    fun `groups follow the addon order and keep global stream indices`() {
        val groups = addonGroups(listOf(beta, alpha), streams)

        assertEquals(listOf(beta, alpha), groups.map { it.addon })
        assertEquals(listOf(listOf(2, 3, 4), listOf(0, 1)), groups.map { it.indices })
    }

    @Test
    fun `an addon that returned nothing keeps its card`() {
        val groups = addonGroups(listOf(alpha, empty, beta), streams)

        assertEquals(listOf("Alpha", "Empty", "Beta"), groups.map { it.addon.name })
        assertEquals(emptyList<Int>(), groups[1].indices)
    }

    @Test
    fun `streams from an unlisted addon get their own card so none is hidden`() {
        val groups = addonGroups(listOf(beta), streams)

        assertEquals(listOf(beta, alpha), groups.map { it.addon })
        assertEquals(listOf(0, 1), groups[1].indices)
        assertEquals(listOf(listOf(0, 1), listOf(2, 3, 4)), addonGroups(emptyList(), streams).map { it.indices })
    }

    @Test
    fun `no addons and no streams means no cards`() {
        assertEquals(emptyList<AddonGroup>(), addonGroups(emptyList(), emptyList()))
    }

    @Test
    fun `a LAST USED stream selects its addon and focuses it`() {
        val groups = addonGroups(listOf(alpha, beta), streams)

        assertEquals(InitialFocus(addonIndex = 1, streamPosition = 2), initialFocus(groups, lastUsedIndex = 4))
        assertEquals(InitialFocus(addonIndex = 0, streamPosition = 1), initialFocus(groups, lastUsedIndex = 1))
    }

    @Test
    fun `without a listed LAST USED the first addon's first stream is focused`() {
        val groups = addonGroups(listOf(alpha, beta), streams)

        assertEquals(InitialFocus(0, 0), initialFocus(groups, lastUsedIndex = null))
        assertEquals(InitialFocus(0, 0), initialFocus(groups, lastUsedIndex = 9))
        assertEquals(InitialFocus(0, 0), initialFocus(groups, lastUsedIndex = -1))
    }

    @Test
    fun `an empty first addon focuses its card`() {
        val groups = addonGroups(listOf(empty, alpha, beta), streams)

        assertEquals(InitialFocus(addonIndex = 0, streamPosition = null), initialFocus(groups, lastUsedIndex = null))
        // A LAST USED in a later addon still wins.
        assertEquals(InitialFocus(addonIndex = 2, streamPosition = 0), initialFocus(groups, lastUsedIndex = 2))
    }

    @Test
    fun `a LAST USED stream far down the addons column selects that addon so the column scrolls to it`() {
        val addons = (1..6).map { StreamAddon("https://a$it", "A$it") }
        val list = addons.flatMap { addon -> listOf(stream(addon, 1), stream(addon, 2)) } // A5's streams are 8, 9
        val groups = addonGroups(addons, list)

        assertEquals(InitialFocus(addonIndex = 4, streamPosition = 1), initialFocus(groups, lastUsedIndex = 9))
    }

    @Test
    fun `no cards means no initial focus target`() {
        assertNull(initialFocus(emptyList(), lastUsedIndex = 0))
    }

    private fun stream(addon: StreamAddon, n: Int) =
        StreamSource(addon.url, addon.name, "${addon.url}/s$n", "${addon.name} $n", "", emptyMap())
}
