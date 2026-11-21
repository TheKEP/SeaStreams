package com.seastreams.tv.ui.theme

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.seastreams.tv.core.datastore.BackgroundSwatches
import com.seastreams.tv.core.datastore.SettingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** PRD §11 colour picker: preview / Enter / Back act on the active profile's background only. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppearanceViewModelTest {

    @TempDir
    lateinit var dir: File

    private lateinit var scope: CoroutineScope
    private lateinit var settings: SettingsDataStore

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        settings = SettingsDataStore(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "a.preferences_pb") })
        runBlocking { settings.setActiveProfileId(A) }
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `preview shows at once, Back reverts and Enter saves to the active profile only`() = runTest {
        val vm = AppearanceViewModel(settings)
        awaitValue(vm.saved, BackgroundSwatches.DEFAULT)

        vm.preview(PLUM)
        assertEquals(PLUM, vm.background.value)
        vm.cancelPreview()
        assertEquals(BackgroundSwatches.DEFAULT, vm.background.value)

        vm.confirm(PLUM)
        awaitValue(vm.saved, PLUM)
        assertEquals(PLUM, vm.background.value)

        settings.setActiveProfileId(B)
        awaitValue(vm.saved, BackgroundSwatches.DEFAULT)
        awaitValue(vm.background, BackgroundSwatches.DEFAULT)
    }

    @Test
    fun `switching profiles drops an unconfirmed preview and shows the new profile's colour`() = runTest {
        val vm = AppearanceViewModel(settings)
        vm.confirm(PLUM)
        awaitValue(vm.saved, PLUM)

        settings.setActiveProfileId(B)
        awaitValue(vm.saved, BackgroundSwatches.DEFAULT)
        vm.preview(TEAL)
        assertEquals(TEAL, vm.background.value)

        settings.setActiveProfileId(A)
        awaitValue(vm.background, PLUM)
        assertEquals(BackgroundSwatches.DEFAULT, readColour(B))
    }

    private suspend fun readColour(profileId: Long): Int {
        settings.setActiveProfileId(profileId)
        return settings.settings.first().appearance.backgroundArgb
    }

    /** DataStore emits from its IO scope, so wait (in real time) for the state to catch up. */
    private suspend fun awaitValue(flow: StateFlow<Int>, expected: Int) = withContext(Dispatchers.Default) {
        withTimeout(5_000) { flow.first { it == expected } }
    }

    private companion object {
        const val A = 1L
        const val B = 2L
        val PLUM = BackgroundSwatches.ALL[2]
        val TEAL = BackgroundSwatches.ALL[4]
    }
}
