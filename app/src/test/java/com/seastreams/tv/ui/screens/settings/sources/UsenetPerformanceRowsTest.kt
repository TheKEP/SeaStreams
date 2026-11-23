package com.seastreams.tv.ui.screens.settings.sources

import com.seastreams.tv.ui.screens.settings.PanelItem
import com.seastreams.tv.domain.usenet.DamagePolicy
import com.seastreams.tv.domain.usenet.UsenetPerformance
import com.seastreams.tv.domain.usenet.UsenetProfile
import com.seastreams.tv.ui.screens.settings.RowTrailing
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UsenetPerformanceRowsTest {

    private var state = UsenetPerformance()
    private val profiles = mutableListOf<UsenetProfile>()

    private fun items() = performanceItems(state, setProfile = { profiles += it; state = state.withProfile(it) }, edit = { state = state.edited(it) })

    private fun row(key: String) = items().filterIsInstance<PanelItem.Row>().single { it.key == key }

    @Test
    fun `sections follow the AIOStreams screenshots and the profile row comes first`() {
        val all = items()
        assertEquals(
            listOf("Performance", "Connections & timeouts", "Reliability", "Archive handling", "Verification", "Import"),
            all.filterIsInstance<PanelItem.Section>().map { it.title },
        )
        assertEquals(PROFILE_KEY, all.filterIsInstance<PanelItem.Row>().first().key)
        assertEquals(25, all.filterIsInstance<PanelItem.Row>().size)
        assertEquals(all.size, all.map { it.key }.toSet().size)
    }

    @Test
    fun `the default Conservative values are labelled like the PRD table`() {
        val values = items().filterIsInstance<PanelItem.Row>().associate { it.key to it.value }
        assertEquals("Conservative", values[PROFILE_KEY])
        assertEquals("16", values["read-ahead"])
        assertEquals("30", values["max-downloads"])
        assertEquals("512 MB", values["disk-cache"])
        assertEquals("0.90", values["priority"])
        assertEquals("1m", values["idle-ttl"])
        assertEquals("1h", values["stream-idle"])
        assertEquals("30m", values["census-lifetime"])
        assertEquals("150 MB", values["max-nzb"])
        assertEquals("On", values["lazy-rar"])
    }

    @Test
    fun `stepping the profile applies templates and stepping a value switches to Custom`() {
        (row(PROFILE_KEY).trailing as RowTrailing.Stepper).onIncrease()
        assertEquals(listOf(UsenetProfile.BALANCED), profiles)
        assertEquals(24, state.readAheadSegments)

        (row("read-ahead").trailing as RowTrailing.Stepper).onIncrease()
        assertEquals(32, state.readAheadSegments)
        assertEquals(UsenetProfile.CUSTOM, state.profile)
        assertEquals("Custom", row(PROFILE_KEY).value)
    }

    @Test
    fun `toggles and choices edit their value and switch to Custom`() {
        row("mkv-repair").onClick()
        assertEquals(false, state.matroskaHoleRepair)
        assertEquals(UsenetProfile.CUSTOM, state.profile)

        (row("damage-policy").trailing as RowTrailing.Stepper).onIncrease()
        assertEquals(DamagePolicy.TOLERANT, state.damagePolicy)
        row("damage-policy").onClick()
        assertEquals(DamagePolicy.STRICT, state.damagePolicy, "OK wraps")
    }

    @Test
    fun `steppers stop at the ends, OK wraps forward and off-list values snap to a neighbour`() {
        val options = listOf(0, 256, 512, 1_024)
        assertEquals(256, stepOption(options, 0, 1))
        assertEquals(1_024, stepOption(options, 1_024, 1))
        assertEquals(0, stepOption(options, 1_024, 1, wrap = true))
        assertEquals(0, stepOption(options, 0, -1))
        assertEquals(512, stepOption(options, 300, 1))
        assertEquals(256, stepOption(options, 300, -1))
        assertEquals(UsenetProfile.CONSERVATIVE, stepChoice(UsenetProfile.entries, UsenetProfile.CONSERVATIVE, -1))
        assertEquals(UsenetProfile.CONSERVATIVE, stepChoice(UsenetProfile.entries, UsenetProfile.CUSTOM, 1, wrap = true))
    }

    @Test
    fun `labels read naturally`() {
        assertEquals("Never", durationLabel(0, zero = "Never"))
        assertEquals("45s", durationLabel(45))
        assertEquals("1m 30s", durationLabel(90))
        assertEquals("2h", durationLabel(7_200))
        assertEquals("Off", megabytesLabel(0))
        assertEquals("512 MB", megabytesLabel(512))
        assertEquals("1.5 GB", megabytesLabel(1_536))
        assertEquals("1.00", shareLabel(100))
        assertTrue(items().filterIsInstance<PanelItem.Row>().none { it.value.isBlank() })
    }
}
