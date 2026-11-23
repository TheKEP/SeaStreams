package com.seastreams.tv.testutil

import com.seastreams.tv.data.addon.UsenetAddonState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** A [UsenetAddonState] driven by [configured] (default: Usenet streaming not set up). */
fun usenetAddonState(configured: Flow<Boolean> = flowOf(false)): UsenetAddonState = mockk {
    every { this@mockk.configured } returns configured
}
