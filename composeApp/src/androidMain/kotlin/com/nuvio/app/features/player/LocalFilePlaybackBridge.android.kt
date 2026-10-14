package com.nuvio.app.features.player

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

internal data class LocalFilePlaybackRequest(
    val uri: String,
    val title: String,
)

/** Delivers Android document URIs from the Activity layer to Compose navigation. */
internal object LocalFilePlaybackBridge {
    private val queue = Channel<LocalFilePlaybackRequest>(capacity = Channel.BUFFERED)
    val requests = queue.receiveAsFlow()

    private val openPickerQueue = Channel<Unit>(capacity = Channel.CONFLATED)
    val openPickerRequests = openPickerQueue.receiveAsFlow()

    fun requestOpenPicker() {
        openPickerQueue.trySend(Unit)
    }

    fun requestPlayback(uri: String, title: String) {
        queue.trySend(LocalFilePlaybackRequest(uri = uri, title = title))
    }
}
