package dev.hazydreams.hermesceleste

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One-shot composer focus requests owned by the Android lifetime adapter. */
internal class ComposerFocusRequests {
    private var nextRequestId = 1L
    private val mutablePending = MutableStateFlow<Long?>(nextRequestId)

    val pending: StateFlow<Long?> = mutablePending.asStateFlow()

    fun request(): Long {
        val requestId = ++nextRequestId
        mutablePending.value = requestId
        return requestId
    }

    fun complete(requestId: Long): Boolean {
        if (mutablePending.value != requestId) return false
        mutablePending.value = null
        return true
    }
}
