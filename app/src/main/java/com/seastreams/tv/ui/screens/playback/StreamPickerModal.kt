package com.seastreams.tv.ui.screens.playback

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import com.seastreams.tv.domain.model.StreamSource

/**
 * Stream selection modal: a [StreamListPanel] listing every stream the enabled addons returned, in
 * addon order, opening focused on the `LAST USED` stream when listed. Focus stays inside the panel
 * and wraps both ways; `Back` closes it.
 */
@Composable
fun StreamPickerModal(state: StreamPickerState, onChoose: (StreamSource) -> Unit, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    StreamListPanel(
        title = state.title,
        subtitle = state.subtitle,
        loading = state.loading,
        streams = state.streams,
        emptyText = "No streams found. Press OK to close.",
        onEmptyClick = onDismiss,
        onChoose = { _, stream -> onChoose(stream) },
        failedAddons = state.failedAddons,
        lastUsedIndex = state.lastUsedIndex,
    )
}
