package com.seastreams.tv.ui.screens.profiles

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.seastreams.tv.core.datastore.SettingsDataStore
import com.seastreams.tv.data.profile.ProfileRepository
import com.seastreams.tv.domain.usecase.GetProfileGateBackdropUseCase
import com.seastreams.tv.testutil.testProfile
import com.seastreams.tv.ui.screens.home.HomeImagePrewarmer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Rule 6: the Profile Gate backdrop must follow Kids profiles being added or removed mid-session. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileSelectViewModelTest {

    private val adult = testProfile(1, isKids = false)
    private val kid = testProfile(2, isKids = true)
    private val profileList = MutableStateFlow(listOf(adult))

    private val repository = mockk<ProfileRepository>()
    private val settings = mockk<SettingsDataStore>()
    private val useCase = mockk<GetProfileGateBackdropUseCase>()
    private val prewarmer = mockk<HomeImagePrewarmer>(relaxed = true)

    /** Lets a test hold the Kids-ceiling (or Adult-ceiling) fetch open until it says so. */
    private val kidsFetch = CompletableDeferred<String?>()
    private val adultFetch = CompletableDeferred<String?>()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { repository.observeProfiles() } returns profileList
        every { settings.activeProfileId } returns flowOf(null)
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = ProfileSelectViewModel(
        profiles = repository,
        settings = settings,
        gateBackdrop = { useCase },
        homeCatalogs = mockk(relaxed = true),
        imagePrewarmer = prewarmer,
        appContext = mockk(relaxed = true),
    )

    /** Skips intermediate states until the backdrop is [url] (the fetch hops to the IO dispatcher). */
    private suspend fun ReceiveTurbine<ProfileSelectUiState>.awaitBackdrop(url: String): ProfileSelectUiState {
        while (true) {
            val state = awaitItem()
            if (state.backdropUrl == url) return state
        }
    }

    @Test
    fun `adding a Kids profile hides the adult backdrop at once and loads a stricter one`() = runTest(UnconfinedTestDispatcher()) {
        coEvery { useCase(false, any()) } returns ADULT_ART
        coEvery { useCase(true, any()) } coAnswers { kidsFetch.await() }
        val viewModel = viewModel()

        viewModel.uiState.test {
            awaitBackdrop(ADULT_ART)

            profileList.value = listOf(adult, kid)
            val withKid = awaitItem()
            assertEquals(listOf(adult, kid), withKid.profiles)
            assertNull(withKid.backdropUrl, "adult-ceiling artwork must not show once a Kids profile exists")
            coVerify(exactly = 1) { useCase(true, any()) }

            kidsFetch.complete(KIDS_ART)
            awaitBackdrop(KIDS_ART)
        }
    }

    @Test
    fun `a failed stricter refetch never brings the adult backdrop back`() = runTest(UnconfinedTestDispatcher()) {
        coEvery { useCase(false, any()) } returns ADULT_ART
        coEvery { useCase(true, any()) } coAnswers { kidsFetch.await() }
        val viewModel = viewModel()

        viewModel.uiState.test {
            awaitBackdrop(ADULT_ART)
            profileList.value = listOf(adult, kid)
            assertNull(awaitItem().backdropUrl)

            kidsFetch.complete(null) // offline / rate-limited
            profileList.value = listOf(adult, kid.copy(name = "Kiddo")) // unrelated edit re-emits state
            val after = awaitItem()
            assertEquals("Kiddo", after.profiles[1].name)
            assertNull(after.backdropUrl)
        }
    }

    @Test
    fun `removing the last Kids profile keeps the strict backdrop until a regular one arrives`() = runTest(UnconfinedTestDispatcher()) {
        profileList.value = listOf(adult, kid)
        coEvery { useCase(true, any()) } returns KIDS_ART
        coEvery { useCase(false, any()) } coAnswers { adultFetch.await() }
        val viewModel = viewModel()

        viewModel.uiState.test {
            awaitBackdrop(KIDS_ART)

            profileList.value = listOf(adult)
            val adultsOnly = awaitItem()
            assertEquals(listOf(adult), adultsOnly.profiles)
            assertEquals(KIDS_ART, adultsOnly.backdropUrl, "stricter artwork is still allowed")

            adultFetch.complete(ADULT_ART)
            awaitBackdrop(ADULT_ART)
        }
    }

    @Test
    fun `profile edits that keep the Kids answer unchanged do not refetch`() = runTest(UnconfinedTestDispatcher()) {
        coEvery { useCase(false, any()) } returns ADULT_ART
        val viewModel = viewModel()

        viewModel.uiState.test {
            awaitBackdrop(ADULT_ART)
            profileList.value = listOf(adult, testProfile(3, isKids = false))
            assertEquals(ADULT_ART, awaitItem().backdropUrl)
        }
        coVerify(exactly = 1) { useCase(any(), any()) }
    }

    private companion object {
        const val ADULT_ART = "https://image.tmdb.org/adult-ceiling.jpg"
        const val KIDS_ART = "https://image.tmdb.org/kids-ceiling.jpg"
    }
}
