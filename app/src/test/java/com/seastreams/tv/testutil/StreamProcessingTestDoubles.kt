package com.seastreams.tv.testutil

import com.seastreams.tv.domain.model.StreamSource
import com.seastreams.tv.domain.streams.ProcessStreamsUseCase
import io.mockk.coEvery
import io.mockk.mockk

/** A [ProcessStreamsUseCase] with every profile on default settings: the stream list passes through untouched. */
fun noStreamProcessing(): ProcessStreamsUseCase = mockk {
    coEvery { this@mockk.invoke(any(), any(), any()) } answers { secondArg<List<StreamSource>>() }
}
