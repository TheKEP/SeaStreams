package com.seastreams.tv.domain.usecase

import com.seastreams.tv.core.util.orNullUnlessCancelled
import com.seastreams.tv.domain.model.CatalogRow
import com.seastreams.tv.domain.model.CatalogRowSpec
import com.seastreams.tv.domain.model.CatalogRowState
import com.seastreams.tv.domain.model.RowContent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Emits every row of [specs] straight away (already loaded when [cached] has it, else loading), then
 * again each time one of the remaining rows finishes ([load] runs for them in parallel). A row whose
 * load throws becomes [RowContent.Failed].
 */
internal fun rowStates(
    specs: List<CatalogRowSpec>,
    cached: (CatalogRowSpec) -> CatalogRow? = { null },
    load: suspend (CatalogRowSpec) -> CatalogRow,
): Flow<List<CatalogRowState>> =
    channelFlow {
        val states = initialRowStates(specs, cached).toMutableList()
        val lock = Mutex()
        send(states.toList())
        specs.forEachIndexed { index, spec ->
            if (states[index].content is RowContent.Loaded) return@forEachIndexed
            launch {
                val content = orNullUnlessCancelled { RowContent.Loaded(load(spec)) } ?: RowContent.Failed
                lock.withLock {
                    states[index] = states[index].copy(content = content)
                    send(states.toList())
                }
            }
        }
    }

/** [specs] as loaded rows where [cached] already has the row, loading placeholders elsewhere. */
internal fun initialRowStates(specs: List<CatalogRowSpec>, cached: (CatalogRowSpec) -> CatalogRow?): List<CatalogRowState> =
    specs.map { spec -> CatalogRowState(spec, cached(spec)?.let { RowContent.Loaded(it) } ?: RowContent.Loading) }
