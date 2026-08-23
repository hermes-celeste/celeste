package dev.hazydreams.hermesceleste.ui.conversation

import dev.hazydreams.hermesceleste.network.BackgroundProcessResult
import dev.hazydreams.hermesceleste.network.BackgroundProcessState
import org.junit.Assert.assertEquals
import org.junit.Test

class ProcessResultSheetTest {
    @Test
    fun completedResultUsesCompactReadableLabels() {
        val result = processResult(
            state = BackgroundProcessState.Completed,
            command = "./gradlew test\n--rerun-tasks",
            output = "BUILD SUCCESSFUL",
        )

        assertEquals("Completed", processResultStatusLabel(result))
        assertEquals("BUILD SUCCESSFUL", processResultOutputText(result))
    }

    @Test
    fun truncatedAndEmptyOutputRemainExplicit() {
        val truncated = processResult(
            state = BackgroundProcessState.Failed,
            output = "last lines",
            truncated = true,
        )
        val empty = processResult(output = "")

        assertEquals("Failed", processResultStatusLabel(truncated))
        assertEquals("… Earlier output omitted\nlast lines", processResultOutputText(truncated))
        assertEquals("No output", processResultOutputText(empty))
    }

    private fun processResult(
        state: BackgroundProcessState = BackgroundProcessState.Completed,
        command: String = "task",
        output: String = "output",
        truncated: Boolean = false,
    ) = BackgroundProcessResult(
        processId = "proc_42",
        state = state,
        status = if (state == BackgroundProcessState.Completed) "Completed normally" else "Exited",
        command = command,
        output = output,
        exitCode = if (state == BackgroundProcessState.Completed) 0 else 1,
        outputTruncated = truncated,
    )
}
