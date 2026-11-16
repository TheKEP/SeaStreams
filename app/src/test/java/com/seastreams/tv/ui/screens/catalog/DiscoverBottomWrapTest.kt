package com.seastreams.tv.ui.screens.catalog

import com.seastreams.tv.domain.model.CatalogRow
import com.seastreams.tv.domain.model.MediaItem
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.RowContent
import com.seastreams.tv.ui.theme.retryEachFrame
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/** Bottom `Streaming services` bar → `Popular Movies` / `Popular Shows` wrap (PRD §1). */
class DiscoverBottomWrapTest {

    private fun loaded(type: MediaType) = RowContent.Loaded(
        CatalogRow(
            listOf(
                MediaItem(
                    tmdbId = 1, mediaType = type, title = "Title", overview = "", posterUrl = null, backdropUrl = null,
                    heroBackdropUrl = null, logoUrl = null, year = null, ratingPercent = null, genres = emptyList(),
                    runtimeMinutes = null, certification = null, isAdultContent = false,
                ),
            ),
            hasMore = false,
        ),
    )

    /** A fake page: records every step in order, with row 0 attaching after [attachAfterFrames] frames. */
    private class FakePage(private val attachAfterFrames: Int, private val focusSucceeds: Boolean = true) {
        val log = mutableListOf<String>()
        var scrolledTo: Pair<Int, Int>? = null
        private var frames = 0
        val awaitFrame: suspend () -> Unit = { frames++; log += "frame$frames" }
        val scrollTo: suspend (Int, Int) -> Unit = { index, offset -> scrolledTo = index to offset; log += "scroll($index,$offset)" }
        val isRowLaidOut: (Int) -> Boolean = { frames >= attachAfterFrames }
        val focusRow: () -> Boolean = { log += "focusRow"; focusSucceeds }
    }

    @ParameterizedTest
    @EnumSource(MediaType::class)
    fun `the wrap target is the Popular row at index 0 on both tabs`(type: MediaType) {
        val rows = listOf(loaded(type), loaded(type), RowContent.Loading)

        assertEquals(0, bottomWrapTargetRow(rows))
    }

    @Test
    fun `with no row holding cards the press stays on the bar and never reaches the nav bar`() {
        assertNull(bottomWrapTargetRow(emptyList()))
        assertNull(bottomWrapTargetRow(listOf(RowContent.Failed, RowContent.Loading)))
    }

    @Test
    fun `a failed Popular row wraps to the next row that has cards`() {
        assertEquals(1, bottomWrapTargetRow(listOf(RowContent.Failed, loaded(MediaType.MOVIE))))
    }

    @ParameterizedTest
    @EnumSource(MediaType::class)
    fun `wrapping scrolls to (0, 0) before focusing the first catalog row`(type: MediaType) = runTest {
        val page = FakePage(attachAfterFrames = 1)
        val target = bottomWrapTargetRow(listOf(loaded(type), loaded(type)))

        val landed = wrapBottomBarToRow(checkNotNull(target), page.scrollTo, page.isRowLaidOut, page.focusRow, page.awaitFrame)

        assertTrue(landed)
        assertEquals(0 to 0, page.scrolledTo)
        assertEquals(listOf("scroll(0,0)", "frame1", "focusRow"), page.log)
    }

    @Test
    fun `focus is not requested while row 0 is still attaching and retries on the next frames`() = runTest {
        val page = FakePage(attachAfterFrames = 3)

        val landed = wrapBottomBarToRow(0, page.scrollTo, page.isRowLaidOut, page.focusRow, page.awaitFrame)

        assertTrue(landed)
        assertEquals(listOf("scroll(0,0)", "frame1", "frame2", "frame3", "focusRow"), page.log)
    }

    @Test
    fun `a rejected focus request is retried on the next frame instead of falling back`() = runTest {
        var calls = 0
        val page = FakePage(attachAfterFrames = 0)

        val landed = wrapBottomBarToRow(0, page.scrollTo, page.isRowLaidOut, { ++calls >= 2 }, page.awaitFrame)

        assertTrue(landed)
        assertEquals(2, calls)
    }

    @Test
    fun `a row that never attaches gives up after the frame budget without any other focus target`() = runTest {
        val page = FakePage(attachAfterFrames = Int.MAX_VALUE)

        val landed = wrapBottomBarToRow(0, page.scrollTo, page.isRowLaidOut, page.focusRow, page.awaitFrame)

        assertFalse(landed)
        assertEquals(WRAP_FOCUS_MAX_FRAMES + 1, page.log.size) // the scroll, then one entry per frame
        assertFalse("focusRow" in page.log)
    }

    @Test
    fun `retryEachFrame waits a frame before every attempt and stops at the first success`() = runTest {
        val log = mutableListOf<String>()
        var attempts = 0

        val ok = retryEachFrame(maxFrames = 5, awaitFrame = { log += "frame" }) { log += "try"; ++attempts == 2 }

        assertTrue(ok)
        assertEquals(listOf("frame", "try", "frame", "try"), log)
    }
}
