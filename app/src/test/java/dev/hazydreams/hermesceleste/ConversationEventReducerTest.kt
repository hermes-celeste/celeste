package dev.hazydreams.hermesceleste

import dev.hazydreams.hermesceleste.network.ConversationMessage
import dev.hazydreams.hermesceleste.network.ConversationStepKind
import dev.hazydreams.hermesceleste.network.GatewayEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationEventReducerTest {
    @Test
    fun reasoningAndToolsShareOneChronologicalStepsRow() {
        val result = reduceEvents(
            event("message.start"),
            event("reasoning.delta", """{"text":"Inspecting"}"""),
            event("reasoning.delta", """{"text":" the gateway."}"""),
            event(
                "tool.start",
                """{"tool_id":"tool-1","name":"read_file","context":"GatewaySessionApi.kt"}""",
            ),
            event(
                "tool.complete",
                """{"tool_id":"tool-1","name":"read_file","summary":"Read the resume projection","result":"42 lines"}""",
            ),
            event("reasoning.delta", """{"text":"The boundary is clear."}"""),
            event("message.complete", """{"content":"Done","status":"complete"}"""),
        )

        val messages = result.projection.messages
        assertEquals(listOf("user", "steps", "assistant"), messages.map { it.role })
        val steps = messages.single { it.role == "steps" }
        assertEquals(
            listOf(ConversationStepKind.Reasoning, ConversationStepKind.Tool, ConversationStepKind.Reasoning),
            steps.steps.map { it.kind },
        )
        assertEquals("Inspecting the gateway.", steps.steps[0].detail)
        assertEquals("tool-1", steps.steps[1].id)
        assertEquals("Read the resume projection", steps.steps[1].summary)
        assertEquals("The boundary is clear.", steps.steps[2].detail)
        assertFalse(steps.pending)
        assertTrue(steps.steps.none { it.pending })
    }

    @Test
    fun structuredToolResultCompletesTheStepAndTurn() {
        val active = reduceEvents(
            event("message.start"),
            event(
                "tool.start",
                """{"tool_id":"tool-json","name":"read_file","context":"report.json"}""",
            ),
            event(
                "tool.complete",
                """{"tool_id":"tool-json","name":"read_file","summary":"Read report","result":{"items":[1,2]}}""",
            ),
        )

        val activeSteps = active.projection.messages.single { it.role == "steps" }
        assertTrue(activeSteps.pending)
        assertFalse(activeSteps.steps.single().pending)

        val completed = active.reduce(event("message.complete", """{"content":"Done","status":"complete"}"""))
        val tool = completed.projection.messages.single { it.role == "steps" }.steps.single()
        assertEquals(TurnState.Idle, completed.projection.turnState)
        assertEquals("Read report", tool.summary)
        assertEquals("{\"items\":[1,2]}", tool.result)
        assertFalse(tool.pending)
    }

    @Test
    fun reasoningWhitespaceDeltasPreserveTokenSpacing() {
        val result = reduceEvents(
            event("message.start"),
            event("reasoning.delta", """{"text":"Inspect"}"""),
            event("reasoning.delta", """{"text":" "}"""),
            event("reasoning.delta", """{"text":"the file"}"""),
            event("message.complete", """{"content":"Done","status":"complete"}"""),
        )

        val reasoning = result.projection.messages
            .single { it.role == "steps" }
            .steps
            .single { it.kind == ConversationStepKind.Reasoning }
        assertEquals("Inspect the file", reasoning.detail)
    }

    @Test
    fun assistantInterimEndsTheCurrentStepsCapsuleAndKeepsItsMessageVisible() {
        val result = reduceEvents(
            event("message.start"),
            event("reasoning.delta", """{"text":"First thought."}"""),
            event("message.interim", """{"text":"I checked the first part."}"""),
            event("reasoning.delta", """{"text":"Second thought."}"""),
            event("message.complete", """{"content":"Finished.","status":"complete"}"""),
        )

        val messages = result.projection.messages
        assertEquals(
            listOf("user", "steps", "assistant", "steps", "assistant"),
            messages.map { it.role },
        )
        assertEquals("I checked the first part.", messages[2].text)
        assertTrue(messages[2].interim)
        assertEquals("First thought.", messages[1].steps.single().detail)
        assertEquals("Second thought.", messages[3].steps.single().detail)
        val thinkingCapsules = messages.filter { it.role == "steps" }
        assertTrue(thinkingCapsules.none { it.pending })
        assertTrue(thinkingCapsules.flatMap { it.steps }.none { it.pending })
    }

    @Test
    fun sameNameParallelToolsCompleteByStableId() {
        val result = reduceEvents(
            event("message.start"),
            event("tool.start", """{"tool_id":"command-a","name":"terminal","context":"first"}"""),
            event("tool.start", """{"tool_id":"command-b","name":"terminal","context":"second"}"""),
            event("tool.complete", """{"tool_id":"command-b","name":"terminal","summary":"Second done"}"""),
            event("tool.complete", """{"tool_id":"command-a","name":"terminal","summary":"First done"}"""),
            event("message.complete", """{"content":"Both done","status":"complete"}"""),
        )

        val tools = result.projection.messages.single { it.role == "steps" }.steps
        assertEquals(listOf("command-a", "command-b"), tools.map { it.id })
        assertEquals(listOf("First done", "Second done"), tools.map { it.summary })
        assertTrue(tools.none { it.pending })
    }

    @Test
    fun compactionStatusFollowsStructuredLifecycleAndResumedOutput() {
        var result = reduceEvents(
            event("message.start"),
            event("status.update", """{"kind":"compacting","text":"server wording is not presentation copy"}"""),
        )
        assertTrue(result.projection.isCompacting)

        result = result.reduce(event("status.update", """{"kind":"process","text":"unrelated background work"}"""))
        assertTrue(result.projection.isCompacting)

        result = result.reduce(event("status.update", """{"kind":"compacted","text":"complete"}"""))
        assertFalse(result.projection.isCompacting)

        result = result
            .reduce(event("status.update", """{"kind":"compacting"}"""))
            .reduce(event("thinking.delta", """{"text":"Waiting for provider"}"""))
        assertFalse(result.projection.isCompacting)
        assertEquals(listOf("user"), result.projection.messages.map { it.role })

        result = result
            .reduce(event("status.update", """{"kind":"compacting"}"""))
            .reduce(event("message.complete", """{"content":"Ready","status":"complete"}"""))
        assertFalse(result.projection.isCompacting)
    }

    @Test
    fun thinkingDeltaDoesNotCreateSteps() {
        val result = reduceEvents(
            event("message.start"),
            event("thinking.delta", """{"text":"Waiting for provider"}"""),
            event("message.complete", """{"content":"Ready","status":"complete"}"""),
        )

        assertEquals(listOf("user", "assistant"), result.projection.messages.map { it.role })
    }

    @Test
    fun generatedIdsAdvanceThroughExplicitReducerState() {
        val initial = ConversationEventReduction(projection(), localMessageCounter = 40L)
        val result = initial
            .reduce(event("tool.start", """{"name":"terminal","context":"first"}"""))
            .reduce(event("tool.complete", """{"name":"terminal","summary":"Done"}"""))

        val tool = result.projection.messages.single { it.role == "steps" }.steps.single()
        assertEquals("tool-41", tool.id)
        assertEquals(44L, result.localMessageCounter)
    }

    private fun reduceEvents(vararg events: GatewayEvent): ConversationEventReduction =
        events.fold(ConversationEventReduction(projection(), localMessageCounter = 0L)) { current, event ->
            current.reduce(event)
        }

    private fun ConversationEventReduction.reduce(event: GatewayEvent): ConversationEventReduction =
        reduceConversationEvent(
            projection = projection,
            event = event,
            localMessageCounter = localMessageCounter,
        )

    private fun projection(): ConversationProjection = ConversationProjection(
        messages = listOf(ConversationMessage(role = "user", text = "Prompt", id = "user-1")),
        streamingText = "",
        turnState = TurnState.Idle,
        isCompacting = false,
        errorMessage = null,
    )

    private fun event(type: String, payload: String = "{}"): GatewayEvent = GatewayEvent(
        type = type,
        sessionId = "runtime-7",
        payload = Json.parseToJsonElement(payload) as JsonObject,
    )
}
