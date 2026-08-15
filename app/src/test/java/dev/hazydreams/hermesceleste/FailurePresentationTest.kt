package dev.hazydreams.hermesceleste

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
    fun usefulFailureTextIsTrimmedAndPreservedForDf07ToExtend() {
        assertEquals(
            "Hermes could not finish that response.",
            sanitizeFailureMessage("  Hermes   could not finish that response.  "),
        )
    }
}
