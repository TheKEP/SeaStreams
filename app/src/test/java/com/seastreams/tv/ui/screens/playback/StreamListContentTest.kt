package com.seastreams.tv.ui.screens.playback

import com.seastreams.tv.domain.model.StreamSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The shared stream list (Details modal and player `Streams` overlay) picks one entry kind. */
class StreamListContentTest {

    @Test
    fun `loading shows the loading entry even when streams are already present`() {
        assertEquals(StreamListContent.LOADING, streamListContent(loading = true, streams = emptyList()))
        assertEquals(StreamListContent.LOADING, streamListContent(loading = true, streams = listOf(stream(1))))
    }

    @Test
    fun `no streams after loading shows the empty entry`() {
        assertEquals(StreamListContent.EMPTY, streamListContent(loading = false, streams = emptyList()))
    }

    @Test
    fun `streams after loading show the stream cards`() {
        assertEquals(StreamListContent.RESULTS, streamListContent(loading = false, streams = listOf(stream(1), stream(2))))
    }

    @Test
    fun `initial focus goes to the LAST USED stream when it is listed`() {
        assertEquals(2, initialStreamIndex(lastUsedIndex = 2, count = 3))
        assertEquals(0, initialStreamIndex(lastUsedIndex = 0, count = 3))
    }

    @Test
    fun `initial focus falls back to the first stream without a listed LAST USED`() {
        assertEquals(0, initialStreamIndex(lastUsedIndex = null, count = 3))
        assertEquals(0, initialStreamIndex(lastUsedIndex = 3, count = 3))
        assertEquals(0, initialStreamIndex(lastUsedIndex = -1, count = 3))
        assertEquals(0, initialStreamIndex(lastUsedIndex = 0, count = 0))
    }

    private fun stream(n: Int) = StreamSource("https://addon", "Addon", "https://s/$n", "Stream $n", "", emptyMap())
}
