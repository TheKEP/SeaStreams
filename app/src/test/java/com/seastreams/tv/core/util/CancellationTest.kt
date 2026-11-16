package com.seastreams.tv.core.util

import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

class CancellationTest {

    @Test
    fun `returns the result, or null when the block fails`() {
        assertEquals(42, orNullUnlessCancelled { 42 })
        assertNull(orNullUnlessCancelled<Int> { throw IOException("offline") })
    }

    @Test
    fun `cancellation is rethrown, never swallowed`() {
        assertThrows<CancellationException> { orNullUnlessCancelled<Int> { throw CancellationException("cancelled") } }
    }
}
