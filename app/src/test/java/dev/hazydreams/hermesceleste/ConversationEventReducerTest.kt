package dev.hazydreams.hermesceleste

import dev.hazydreams.hermesceleste.network.ConversationMessage
import dev.hazydreams.hermesceleste.network.ConversationStepKind
import dev.hazydreams.hermesceleste.network.GatewayEvent
import dev.hazydreams.hermesceleste.network.TaskItemStatus
import dev.hazydreams.hermesceleste.network.changedFiles
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationEventReducerTest {
    @Test
    fun reasoningAroundFileEditsKeepsOneThinkingRowAndOneTurnLevelChangesPill() {
        val result = reduceEvents(
            event("message.start"),
            event("reasoning.delta", """{"text":"Choose the first edit."}"""),
            event(
                "tool.start",
                """{"tool_id":"edit-a","name":"patch","args":{"path":"ui/ConversationScreen.kt"}}""",
            ),
            event(
                "tool.complete",
                """{"tool_id":"edit-a","name":"patch","args":{"path":"ui/ConversationScreen.kt"},"inline_diff":"  ┊ review diff\na/ui/ConversationScreen.kt → b/ui/ConversationScreen.kt\n@@ -1 +1 @@\n-old\n+new"}""",
            ),
            event("reasoning.delta", """{"text":"Check the second boundary."}"""),
            event(
                "tool.start",
                """{"tool_id":"edit-b","name":"write_file","args":{"path":"network/WorkSurfaces.kt"}}""",
            ),
            event(
                "tool.complete",
                """{"tool_id":"edit-b","name":"write_file","args":{"path":"network/WorkSurfaces.kt"},"result":{"bytes_written":240}}""",
            ),
            event("message.complete", """{"content":"Both files are updated.","status":"complete"}"""),
        )

        assertEquals(listOf("user", "steps", "assistant", "changes"), result.projection.messages.map { it.role })
        val thinking = result.projection.messages.single { it.role == "steps" }
        assertEquals(
            listOf("Choose the first edit.", "Check the second boundary."),
            thinking.steps.map { it.detail },
        )
        val changes = result.projection.messages.single { it.role == "changes" }.changedFiles()
        assertEquals(listOf("ui/ConversationScreen.kt", "network/WorkSurfaces.kt"), changes.map { it.path })
        assertEquals(2, changes.size)
        assertEquals(1, changes.sumOf { it.additions })
        assertEquals(1, changes.sumOf { it.removals })
    }

    @Test
    fun repeatedEditsToOnePathRemainOneChangedFileWithBothOperations() {
        val result = reduceEvents(
            event("message.start"),
            event("tool.start", """{"tool_id":"edit-a","name":"patch","args":{"path":"App.kt"}}"""),
            event(
                "tool.complete",
                """{"tool_id":"edit-a","name":"patch","args":{"path":"App.kt"},"inline_diff":"a/App.kt → b/App.kt\n@@\n-old\n+first"}""",
            ),
            event("reasoning.delta", """{"text":"Refine the same file."}"""),
            event("tool.start", """{"tool_id":"edit-b","name":"patch","args":{"path":"App.kt"}}"""),
            event(
                "tool.complete",
                """{"tool_id":"edit-b","name":"patch","args":{"path":"App.kt"},"inline_diff":"a/App.kt → b/App.kt\n@@\n-first\n+second"}""",
            ),
        )

        val changes = result.projection.messages.single { it.role == "changes" }.changedFiles()
        assertEquals(1, changes.size)
        assertEquals("App.kt", changes.single().path)
        assertEquals(2, changes.single().diffs.size)
    }

    @Test
    fun todoSnapshotsUpdateComposerProgressWithoutCreatingThinkingSteps() {
        val started = reduceEvents(
            event("message.start"),
            event(
                "tool.start",
                """{"tool_id":"todo-1","name":"todo","args":{"todos":[{"id":"design","content":"Design","status":"in_progress"}]}}""",
            ),
        )

        assertEquals(listOf("design"), started.projection.taskProgress?.items?.map { it.id })

        val progressed = started.reduce(
            event(
                "tool.progress",
                """{"tool_id":"todo-1","todos":[{"id":"design","content":"Design","status":"completed"},{"id":"build","content":"Build","status":"in_progress"}]}""",
            ),
        )
        assertEquals(listOf("design", "build"), progressed.projection.taskProgress?.items?.map { it.id })

        val result = progressed.reduce(
            event(
                "tool.complete",
                """{"tool_id":"todo-1","name":"todo","todos":[{"id":"design","content":"Design","status":"completed"},{"id":"build","content":"Build","status":"in_progress"},{"id":"verify","content":"Verify","status":"pending"}]}""",
            ),
        )

        assertEquals(listOf("user"), result.projection.messages.map { it.role })
        val tasks = result.projection.taskProgress!!
        assertEquals(1, tasks.completedCount)
        assertEquals(3, tasks.totalCount)
        assertEquals(TaskItemStatus.InProgress, tasks.items[1].status)
        assertEquals(listOf("design", "build", "verify"), tasks.items.map { it.id })
    }

    @Test
    fun taskLifecycleClearsExplicitEmptyAndUnfinishedTurnEndButKeepsFinishedSnapshot() {
        val active = reduceEvents(
            event("message.start"),
            event(
                "tool.complete",
                """{"name":"todo","todos":[{"id":"build","content":"Build","status":"in_progress"}]}""",
            ),
        )

        assertNull(active.reduce(event("message.complete")).projection.taskProgress)

        val finished = reduceEvents(
            event("message.start"),
            event(
                "tool.complete",
                """{"name":"todo","todos":[{"id":"build","content":"Build","status":"completed"}]}""",
            ),
        ).reduce(event("message.complete"))

        assertEquals(1, finished.projection.taskProgress?.completedCount)
        assertNull(
            finished.reduce(
                event("tool.complete", """{"name":"todo","todos":[]}"""),
            ).projection.taskProgress,
        )
    }

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
    fun finalAnswerEchoedThroughReasoningSettlesToOneAssistantMessage() {
        val answer = "hello from inside the little app we built"
        val result = reduceEvents(
            event("message.start"),
            event("message.delta", """{"text":"$answer"}"""),
            event("reasoning.delta", """{"text":"$answer"}"""),
            event("message.complete", """{"content":"$answer","status":"complete"}"""),
        )

        assertEquals(listOf("user", "assistant"), result.projection.messages.map { it.role })
        assertEquals(answer, result.projection.messages.single { it.role == "assistant" }.text)
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
    fun changedFilesStayAtTheEndWithoutReorderingCommentaryAndLaterActivity() {
        val result = reduceEvents(
            event("message.start"),
            event("reasoning.delta", """{"text":"Plan the edit."}"""),
            event("tool.start", """{"tool_id":"edit-a","name":"patch","args":{"path":"App.kt"}}"""),
            event(
                "tool.complete",
                """{"tool_id":"edit-a","name":"patch","args":{"path":"App.kt"},"inline_diff":"a/App.kt → b/App.kt\n@@\n-old\n+new"}""",
            ),
            event("reasoning.delta", """{"text":"Check the first result."}"""),
            event("message.interim", """{"text":"The edit is in; I’m checking it."}"""),
            event("reasoning.delta", """{"text":"Run the focused tests."}"""),
            event("tool.start", """{"tool_id":"test-a","name":"terminal","context":"./gradlew focusedTest"}"""),
            event("tool.complete", """{"tool_id":"test-a","name":"terminal","summary":"Tests passed"}"""),
            event("reasoning.delta", """{"text":"Verify the diff."}"""),
            event("tool.start", """{"tool_id":"read-a","name":"read_file","context":"App.kt"}"""),
            event("tool.complete", """{"tool_id":"read-a","name":"read_file","summary":"Read App.kt"}"""),
            event("message.complete", """{"content":"Everything checks out.","status":"complete"}"""),
        )

        assertEquals(
            listOf("user", "steps", "assistant", "steps", "assistant", "changes"),
            result.projection.messages.map { it.role },
        )
        assertEquals("The edit is in; I’m checking it.", result.projection.messages[2].text)
        assertEquals("Everything checks out.", result.projection.messages[4].text)
        val thinking = result.projection.messages.filter { it.role == "steps" }
        assertEquals(2, thinking.size)
        assertEquals(
            listOf(ConversationStepKind.Reasoning, ConversationStepKind.Tool, ConversationStepKind.Reasoning, ConversationStepKind.Tool),
            thinking[1].steps.map { it.kind },
        )
        assertEquals(listOf("App.kt"), result.projection.messages.last().changedFiles().map { it.path })
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
    fun processCompletionProjectsOneCompactResultBeforeTheAssistantResponse() {
        val completion = """[IMPORTANT: Background process proc_42 completed normally (exit code 0).
Command: ./gradlew test
Output:
BUILD SUCCESSFUL
]""".trimIndent()
        val processEvent = GatewayEvent(
            type = "status.update",
            sessionId = "runtime-7",
            payload = buildJsonObject {
                put("kind", "process")
                put("text", completion)
            },
        )

        val result = reduceEvents(
            processEvent,
            processEvent,
            event("message.start"),
            event("message.complete", """{"content":"The checks passed.","status":"complete"}"""),
        )

        assertEquals(listOf("user", "process", "assistant"), result.projection.messages.map { it.role })
        val process = result.projection.messages.single { it.role == "process" }
        assertEquals("process:proc_42", process.id)
        assertEquals("proc_42", process.processResult?.processId)
        assertEquals("./gradlew test", process.processResult?.command)
        assertEquals("BUILD SUCCESSFUL", process.processResult?.output)
        assertEquals(0, process.processResult?.exitCode)
    }

    @Test
    fun processCompletionDoesNotSplitTheActiveToolSteps() {
        val completion = """[IMPORTANT: Background process proc_old completed normally (exit code 0).
Command: ./gradlew test
Output:
BUILD SUCCESSFUL
]""".trimIndent()
        val processEvent = GatewayEvent(
            type = "status.update",
            sessionId = "runtime-7",
            payload = buildJsonObject {
                put("kind", "process")
                put("text", completion)
            },
        )

        val result = reduceEvents(
            event("message.start"),
            event(
                "tool.start",
                """{"tool_id":"tool-current","name":"read_file","context":"Current.kt"}""",
            ),
            processEvent,
            event(
                "tool.complete",
                """{"tool_id":"tool-current","name":"read_file","summary":"Read current file","result":"42 lines"}""",
            ),
            event("message.complete", """{"content":"Done","status":"complete"}"""),
        )

        assertEquals(listOf("user", "steps", "process", "assistant"), result.projection.messages.map { it.role })
        val thinking = result.projection.messages.single { it.role == "steps" }
        val tool = thinking.steps.single()
        assertEquals("tool-current", tool.id)
        assertEquals("Read current file", tool.summary)
        assertFalse(thinking.pending)
        assertFalse(tool.pending)
    }

    @Test
    fun clarificationLivesInlineWhilePendingAndSettlesBeforeWorkContinues() {
        val pending = reduceEvents(
            event("message.start"),
            event("reasoning.delta", """{"text":"I need one decision."}"""),
            event(
                "tool.start",
                """{"tool_id":"clarify-1","name":"clarify","args":{"question":"Which target?","choices":["Staging","Production"],"multi_select":false}}""",
            ),
            event(
                "clarify.request",
                """{"request_id":"request-1","question":"Which target?","choices":["Staging (Recommended)","Production"]}""",
            ),
        )

        assertEquals(listOf("user", "steps", "clarification"), pending.projection.messages.map { it.role })
        val request = pending.projection.messages.last()
        assertTrue(request.pending)
        assertEquals("request-1", request.clarification?.requestId)
        assertEquals(listOf("Staging (Recommended)", "Production"), request.clarification?.choices)
        assertEquals(TurnState.Running, pending.projection.turnState)

        val completed = pending
            .reduce(
                event(
                    "tool.complete",
                    """{"tool_id":"clarify-1","name":"clarify","result":{"question":"Which target?","choices_offered":["Staging","Production"],"user_response":"Staging"}}""",
                ),
            )
            .reduce(event("reasoning.delta", """{"text":"Continuing with staging."}"""))

        assertEquals(
            listOf("user", "steps", "clarification", "steps"),
            completed.projection.messages.map { it.role },
        )
        val settled = completed.projection.messages[2]
        assertFalse(settled.pending)
        assertEquals("Staging", settled.clarification?.answer)
        assertEquals("Continuing with staging.", completed.projection.messages.last().steps.single().detail)

        val replayed = completed.reduce(
            event(
                "clarify.request",
                """{"request_id":"request-1","question":"Which target?","choices":["Staging (Recommended)","Production"]}""",
            ),
        )
        assertEquals(1, replayed.projection.messages.count { it.role == "clarification" })
        assertFalse(replayed.projection.messages.single { it.role == "clarification" }.pending)

        val replayedStart = replayed.reduce(
            event(
                "tool.start",
                """{"tool_id":"clarify-1","name":"clarify","args":{"question":"Which target?","choices":["Staging","Production"]}}""",
            ),
        )
        assertEquals(1, replayedStart.projection.messages.count { it.role == "clarification" })
        assertFalse(replayedStart.projection.messages.single { it.role == "clarification" }.pending)
    }

    @Test
    fun unfinishedClarificationDoesNotLeaveADeadInteractiveCard() {
        val completed = reduceEvents(
            event(
                "clarify.request",
                """{"request_id":"request-1","question":"Which target?","choices":["Staging","Production"]}""",
            ),
            event("message.complete", """{"content":"Timed out.","status":"complete"}"""),
        )

        assertEquals(listOf("user", "assistant"), completed.projection.messages.map { it.role })
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
        taskProgress = null,
        errorMessage = null,
    )

    private fun event(type: String, payload: String = "{}"): GatewayEvent = GatewayEvent(
        type = type,
        sessionId = "runtime-7",
        payload = Json.parseToJsonElement(payload) as JsonObject,
    )
}
