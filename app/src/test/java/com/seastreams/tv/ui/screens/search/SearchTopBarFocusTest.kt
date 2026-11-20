package com.seastreams.tv.ui.screens.search

import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Search top row ⇄ recent chips (PRD §7): `Down` always lands on chip #1, `Up` returns to the origin. */
class SearchTopBarFocusTest {

    private val focused = mutableListOf<FocusRequester>()

    private val mic = FocusRequester()

    /** Scrolls apply on the spot (no layout pass in unit tests); focus requests are recorded. */
    private fun router(focusSucceeds: Boolean = true) = SearchTopBarFocus(
        scrollToStart = { it.requestScrollToItem(0) },
        requestFocus = { focused += it; focusSucceeds },
    )

    /** A router whose focus requests fail for the requesters [failing] picks out. */
    private fun routerFailing(failing: (SearchTopBarFocus, FocusRequester) -> Boolean): SearchTopBarFocus {
        lateinit var focus: SearchTopBarFocus
        focus = SearchTopBarFocus(
            scrollToStart = { it.requestScrollToItem(0) },
            requestFocus = { focused += it; !failing(focus, it) },
        )
        return focus
    }

    @Test
    fun `Up from the content with chips lands on chip #1 with the row scrolled to its start`() = runTest {
        val focus = router()
        focus.onTopFocused(TopBarControl.FIELD)
        focus.chipsState.requestScrollToItem(3)
        assertTrue(focus.enterFromContent(hasChips = true, clearShown = false, mic = mic))
        assertEquals(0, focus.chipsState.firstVisibleItemIndex)
        assertSame(focus.firstChip, focused.single())
    }

    @Test
    fun `Up from the content without chips lands on the mic by default`() = runTest {
        assertTrue(router().enterFromContent(hasChips = false, clearShown = false, mic = mic))
        assertSame(mic, focused.single())
    }

    @Test
    fun `Up from the content without chips returns to the last focused top control`() = runTest {
        val focus = router()
        focus.onTopFocused(TopBarControl.FIELD)
        focus.enterFromContent(hasChips = false, clearShown = false, mic = mic)
        assertSame(focus.field, focused.single())
    }

    @Test
    fun `a vanished clear X sends Up from the content to the field`() = runTest {
        val focus = router()
        focus.onTopFocused(TopBarControl.CLEAR)
        focus.enterFromContent(hasChips = false, clearShown = false, mic = mic)
        assertSame(focus.field, focused.single())
    }

    @Test
    fun `a failed chip request falls back to the top bar`() = runTest {
        val focus = routerFailing { f, r -> r === f.firstChip }
        assertTrue(focus.enterFromContent(hasChips = true, clearShown = false, mic = mic))
        assertEquals(listOf(focus.firstChip, mic), focused)
    }

    @Test
    fun `a failed field request falls back to the mic`() = runTest {
        val focus = routerFailing { f, r -> r === f.field }
        focus.onTopFocused(TopBarControl.FIELD)
        assertTrue(focus.enterFromContent(hasChips = false, clearShown = false, mic = mic))
        assertEquals(listOf(focus.field, mic), focused)
    }

    @Test
    fun `when every request fails focus stays put`() = runTest {
        assertFalse(router(focusSucceeds = false).enterFromContent(hasChips = true, clearShown = false, mic = mic))
    }

    @Test
    fun `Down scrolls a sideways-scrolled chip row back to its start and focuses chip #1`() = runTest {
        val focus = router()
        focus.chipsState.requestScrollToItem(4)
        assertTrue(focus.focusFirstChip())
        assertEquals(0, focus.chipsState.firstVisibleItemIndex)
        assertEquals(1, focused.size)
        assertSame(focus.firstChip, focused.single())
    }

    @Test
    fun `Down reports a failed focus request`() = runTest {
        assertFalse(router(focusSucceeds = false).focusFirstChip())
    }

    @Test
    fun `Up returns to the mic by default`() {
        assertEquals(TopBarControl.MIC, router().upTarget(clearShown = false))
        assertEquals(TopBarControl.MIC, router().upTarget(clearShown = true))
    }

    @Test
    fun `Up returns to the top-row control focus came down from`() {
        val focus = router()
        focus.onTopFocused(TopBarControl.FIELD)
        assertEquals(TopBarControl.FIELD, focus.upTarget(clearShown = false))
        focus.onTopFocused(TopBarControl.MIC)
        assertEquals(TopBarControl.MIC, focus.upTarget(clearShown = false))
    }

    @Test
    fun `Up returns to the clear X only while it is shown, else to the field`() {
        val focus = router()
        focus.onTopFocused(TopBarControl.CLEAR)
        assertEquals(TopBarControl.CLEAR, focus.upTarget(clearShown = true))
        assertEquals(TopBarControl.FIELD, focus.upTarget(clearShown = false))
    }
}
