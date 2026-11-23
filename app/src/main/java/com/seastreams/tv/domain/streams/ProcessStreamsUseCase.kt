package com.seastreams.tv.domain.streams

import com.seastreams.tv.core.datastore.StreamProcessingStore
import com.seastreams.tv.core.util.DefaultDispatcher
import com.seastreams.tv.domain.model.StreamSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Clock
import javax.inject.Inject

/**
 * Applies [profileId]'s stream filters, sort lists and formatter to a resolved stream list ([StreamPipeline]). Runs
 * inside `ResolveStreamsUseCase`, so auto-play, failover order, `LAST USED` and the Addons column counts all see the
 * processed list. The regex work runs on the default (CPU) dispatcher.
 */
class ProcessStreamsUseCase @Inject constructor(
    private val store: StreamProcessingStore,
    private val clock: Clock,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) {
    suspend operator fun invoke(profileId: Long, streams: List<StreamSource>, context: StreamContext): List<StreamSource> {
        if (streams.isEmpty()) return streams
        val settings = store.settings(profileId).first()
        // Nothing stored, or every stage with rules switched off by its master toggle.
        if (settings.effective().isDefault) return streams
        return withContext(dispatcher) { StreamPipeline.process(streams, settings, context, clock.millis()) }
    }
}
