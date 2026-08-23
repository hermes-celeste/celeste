package dev.hazydreams.hermesceleste.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundProcessResultTest {
    @Test
    fun parsesSuccessfulCompletionWithCommandAndOutput() {
        val result = parseBackgroundProcessResult(
            """[IMPORTANT: Background process proc_42 completed normally (exit code 0).
Command: ./gradlew test
Output:
BUILD SUCCESSFUL
]""".trimIndent(),
        )

        requireNotNull(result)
        assertEquals("proc_42", result.processId)
        assertEquals(BackgroundProcessState.Completed, result.state)
        assertEquals("Completed normally", result.status)
        assertEquals("./gradlew test", result.command)
        assertEquals("BUILD SUCCESSFUL", result.output)
        assertEquals(0, result.exitCode)
        assertFalse(result.outputTruncated)
    }

    @Test
    fun parsesFailedCompletionWithAttributionAndSignalMetadata() {
        val result = parseBackgroundProcessResult(
            """[IMPORTANT: Background process proc_99 terminated by SIGTERM (exit code 143, SIGTERM).
Started from a previous turn.
Command: ./server
Output:
Shutting down
]""".trimIndent(),
        )

        requireNotNull(result)
        assertEquals(BackgroundProcessState.Failed, result.state)
        assertEquals("Terminated by SIGTERM", result.status)
        assertEquals("./server", result.command)
        assertEquals("Shutting down", result.output)
        assertEquals(143, result.exitCode)
    }

    @Test
    fun ignoresNonCompletionProcessStatus() {
        assertNull(parseBackgroundProcessResult("Background process proc_42 is still running"))
        assertNull(
            parseBackgroundProcessResult(
                """[IMPORTANT: Background process proc_42 matched "ready".
Output:
ready
]""".trimIndent(),
            ),
        )
    }

    @Test
    fun boundsLongOutputAndKeepsTheNewestTail() {
        val older = "a".repeat(2_000)
        val newest = "z".repeat(MAX_PROCESS_OUTPUT_CHARS)
        val result = parseBackgroundProcessResult(
            """[IMPORTANT: Background process proc_long completed normally (exit code 0).
Command: long-task
Output:
$older$newest
]""".trimIndent(),
        )

        requireNotNull(result)
        assertTrue(result.outputTruncated)
        assertEquals(MAX_PROCESS_OUTPUT_CHARS, result.output.length)
        assertEquals(newest, result.output)
    }
}
