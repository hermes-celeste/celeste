package dev.hazydreams.hermesceleste

import java.io.IOException

import dev.hazydreams.hermesceleste.network.AuthenticationRejected
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FailurePresentationTest {
    @Test
    fun expectedCancellationTextIsSuppressedByTheSharedSeam() {
        assertNull(sanitizeFailureMessage("StandaloneCoroutine was cancelled"))
        assertNull(sanitizeFailure(CancellationException("job cancelled")))
    }

    @Test
    fun allowlistedFailureCopyIsNormalizedAndPreserved() {
        assertEquals(
            "Hermes could not finish that response.",
            sanitizeFailureMessage("  Hermes   could not finish that response.  "),
        )
    }

    @Test
    fun rawFailurePayloadsNeverReachPresentation() {
        val raw = "POST https://private.test/api/session?token=secret failed at /home/user/.ssh/id_rsa: password=secret"

        assertEquals(
            "Could not reconnect to Hermes.",
            sanitizeFailure(IOException(raw), "Could not reconnect to Hermes."),
        )
        assertEquals(
            "Could not reconnect to Hermes.",
            sanitizeFailureMessage(raw, "Could not reconnect to Hermes."),
        )
        assertNull(sanitizeFailureMessage(raw))
        assertEquals(
            "Hermes rejected the dashboard credential. Sign in again.",
            sanitizeFailure(AuthenticationRejected(raw)),
        )
        assertNull(sanitizeFailure(IOException(raw), raw))
    }
}
