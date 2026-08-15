package dev.hazydreams.hermesceleste

import dev.hazydreams.hermesceleste.network.ConversationMessage
import dev.hazydreams.hermesceleste.network.InflightCorrection
import dev.hazydreams.hermesceleste.network.ResumedSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveTurnProjectionTest {
    @Test
    fun correctionOffsetsPreserveArrivalOrderAcrossTheAssistantTail() {
        val projection = projectResumedTurn(
            ResumedSession(
                runtimeSessionId = "runtime-new",
                storedSessionId = "stored-1",
                messages = listOf(
                    ConversationMessage(role = "user", text = "original", id = "user-1"),
                    ConversationMessage(role = "assistant", text = "before", id = "assistant-1"),
                ),
                running = true,
                inflightAssistantText = "beforeoneaftertwo",
                inflightCorrections = listOf(
                    InflightCorrection("first correction", assistantOffset = 6),
                    InflightCorrection("second correction", assistantOffset = 14),
                ),
                correctionOffsets = listOf(6, 14),
                inflightStreaming = true,
                hasLiveProjection = true,
            ),
        )

        assertEquals(
            listOf("user", "assistant", "user", "assistant", "user"),
            projection.messages.map { it.role },
        )
        assertEquals(
            listOf("original", "before", "first correction", "oneafter", "second correction"),
            projection.messages.map { it.text },
        )
        assertTrue(projection.messages[2].pending)
        assertTrue(projection.messages[4].pending)
        assertEquals("two", projection.streamingText)
    }

    @Test
    fun resumeProjectionAddsInflightUserAndCorrectionsOnlyOnce() {
        val resumed = ResumedSession(
            runtimeSessionId = "runtime-new",
            storedSessionId = "stored-1",
            messages = listOf(
                ConversationMessage(role = "user", text = "original", id = "user-1"),
            ),
            running = true,
            inflightUserText = "original",
            inflightAssistantText = "working",
            inflightCorrections = listOf(InflightCorrection("keep going")),
            hasLiveProjection = true,
        )

        val first = projectResumedTurn(resumed)
        val second = projectResumedTurn(resumed.copy(messages = first.messages))

        assertEquals(1, first.messages.count { it.role == "user" && it.text == "original" })
        assertEquals(1, first.messages.count { it.role == "user" && it.text == "keep going" })
        assertEquals(1, second.messages.count { it.role == "user" && it.text == "keep going" })
    }
}
