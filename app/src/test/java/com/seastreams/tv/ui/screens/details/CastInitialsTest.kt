package com.seastreams.tv.ui.screens.details

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CastInitialsTest {

    @Test
    fun `fallback avatar uses first and last initials`() {
        assertEquals("JC", initials("Jill Culton"))
        assertEquals("SI", initials("Suzy Eddie  Izzard"))
        assertEquals("Z", initials("Zendaya"))
        assertEquals("HE", initials(" h.e.r. eddie "))
        assertEquals("", initials("   "))
    }
}
