package com.seastreams.tv.ui.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalTime
import java.util.Locale

class ClockFormatTest {

    @Test
    fun `clock uses 12-hour time with AM and PM`() {
        assertEquals("12:15 AM", formatClockTime(LocalTime.of(0, 15)))
        assertEquals("9:05 AM", formatClockTime(LocalTime.of(9, 5)))
        assertEquals("12:00 PM", formatClockTime(LocalTime.NOON))
        assertEquals("11:59 PM", formatClockTime(LocalTime.of(23, 59)))
    }

    @Test
    fun `clock text does not depend on the device locale`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("1:30 PM", formatClockTime(LocalTime.of(13, 30)))
        } finally {
            Locale.setDefault(original)
        }
    }
}
