package com.seastreams.tv.data.setup

import com.seastreams.tv.core.database.dao.ProfileDao
import com.seastreams.tv.core.database.entity.ProfileEntity
import com.seastreams.tv.data.addon.AddonRepository
import com.seastreams.tv.data.addon.UsenetAddonState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class BuiltInAddonGuardTest {

    @Test
    fun `a failed Usenet entry pass doesn't end the upkeep`() = runTest {
        val profiles = MutableStateFlow(listOf(profile(1)))
        val repository = mockk<AddonRepository> {
            coEvery { ensureUsenet() } throws IllegalStateException("FOREIGN KEY constraint failed") andThen Unit
        }
        val guard = BuiltInAddonGuard(
            repository,
            mockk<UsenetAddonState> { every { configured } returns MutableStateFlow(true) },
            mockk<ProfileDao> { every { observeAll() } returns profiles },
        )

        val upkeep = launch { guard.keepUsenetEntries() }
        advanceUntilIdle()
        profiles.value = listOf(profile(1), profile(2))
        advanceUntilIdle()

        coVerify(exactly = 2) { repository.ensureUsenet() }
        upkeep.cancel()
    }

    private fun profile(id: Long) =
        ProfileEntity(id = id, name = "P$id", avatarIndex = 0, isKids = false, movieCeiling = null, tvCeiling = null, allowUnrated = true, createdAtMillis = 0)
}
