package com.seastreams.tv.testutil

import com.seastreams.tv.domain.model.StreamSource
import com.seastreams.tv.player.external.ExternalStreamUrls
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs

/** An [ExternalStreamUrls] that hands every stream over unchanged (no Usenet, no proxy service). */
fun passThroughExternalUrls(): Lazy<ExternalStreamUrls> {
    val urls = mockk<ExternalStreamUrls> {
        coEvery { prepare(any(), any()) } answers { firstArg<StreamSource>() }
        every { finish() } just runs
    }
    return Lazy { urls }
}
