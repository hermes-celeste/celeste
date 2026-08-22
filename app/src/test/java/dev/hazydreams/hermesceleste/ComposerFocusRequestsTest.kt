package dev.hazydreams.hermesceleste

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerFocusRequestsTest {
    @Test
    fun launchRequestIsConsumedOnceAndLaterNewConversationsReceiveFreshRequests() {
        val requests = ComposerFocusRequests()

        val launchRequest = requests.pending.value
        assertEquals(1L, launchRequest)
        assertTrue(requests.complete(launchRequest!!))
        assertNull(requests.pending.value)
        assertFalse(requests.complete(launchRequest))

        val newConversationRequest = requests.request()
        assertEquals(2L, newConversationRequest)
        assertEquals(newConversationRequest, requests.pending.value)
        assertTrue(requests.complete(newConversationRequest))
        assertNull(requests.pending.value)
    }

    @Test
    fun staleCompletionCannotConsumeANewerFocusRequest() {
        val requests = ComposerFocusRequests()
        val launchRequest = requests.pending.value!!
        val newerRequest = requests.request()

        assertFalse(requests.complete(launchRequest))
        assertEquals(newerRequest, requests.pending.value)
    }
}
