package com.seastreams.tv.ui.screens.playback

import com.seastreams.tv.domain.model.StreamAddon
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The shared stream panel (Details modal and player `Streams` overlay) picks one content kind. */
class StreamListContentTest {

    private val one = listOf(AddonGroup(StreamAddon("https://a", "A"), listOf(0)))
    private val emptyAddon = listOf(AddonGroup(StreamAddon("https://a", "A"), emptyList()))

    @Test
    fun `loading shows the loading entry even when addon cards are already known`() {
        assertEquals(StreamListContent.LOADING, streamListContent(loading = true, groups = emptyList()))
        assertEquals(StreamListContent.LOADING, streamListContent(loading = true, groups = one))
    }

    @Test
    fun `no addon cards after loading shows the empty entry`() {
        assertEquals(StreamListContent.EMPTY, streamListContent(loading = false, groups = emptyList()))
    }

    @Test
    fun `addon cards show results even when every addon returned nothing`() {
        assertEquals(StreamListContent.RESULTS, streamListContent(loading = false, groups = one))
        assertEquals(StreamListContent.RESULTS, streamListContent(loading = false, groups = emptyAddon))
    }
}
