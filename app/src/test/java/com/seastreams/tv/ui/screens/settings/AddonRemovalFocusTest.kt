package com.seastreams.tv.ui.screens.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Manage addons (PRD §10): where focus goes when the addon at an index is removed. */
class AddonRemovalFocusTest {

    private val allRemovable: (Int) -> Boolean = { true }

    @Test
    fun `removing a middle addon focuses the next addon's Remove button`() {
        assertEquals(AddonRemovalFocus.Row(3, AddonRowControl.REMOVE), addonRemovalFocus(2, 5, allRemovable))
    }

    @Test
    fun `removing the first of several focuses the second addon`() {
        assertEquals(AddonRemovalFocus.Row(1, AddonRowControl.REMOVE), addonRemovalFocus(0, 3, allRemovable))
    }

    @Test
    fun `a built-in next addon has no Remove, so its toggle takes focus`() {
        assertEquals(AddonRemovalFocus.Row(2, AddonRowControl.TOGGLE), addonRemovalFocus(1, 4) { it != 2 })
    }

    @Test
    fun `removing the last addon focuses Install more addons`() {
        assertEquals(AddonRemovalFocus.InstallMore, addonRemovalFocus(4, 5, allRemovable))
    }

    @Test
    fun `removing the only addon focuses Install more addons`() {
        assertEquals(AddonRemovalFocus.InstallMore, addonRemovalFocus(0, 1, allRemovable))
    }
}
