package dev.hazydreams.hermesceleste.network

import dev.hazydreams.hermesceleste.ComposerAttachmentKind
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HermesGatewayTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun websocketOpenDoesNotReportConnectedUntilGatewayReady() = runBlocking {
        lateinit var serverSocket: WebSocket
        val upgraded = CompletableDeferred<Unit>()
        server.enqueue(
            MockResponse.Builder()
                .webSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            serverSocket = webSocket
                            upgraded.complete(Unit)
                        }

                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            webSocket.close(code, reason)
                        }
                    },
                )
                .build(),
        )
        val gateway = gateway()

        val connecting = async { gateway.connect() }
        withTimeout(5_000) { upgraded.await() }
        assertEquals(GatewayConnectionState.Connecting, gateway.state.value)
        serverSocket.send(gatewayReadyFrame)
        withTimeout(5_000) { connecting.await() }

        assertEquals(GatewayConnectionState.Connected, gateway.state.value)
        gateway.close()
    }

    @Test
    fun unauthorizedWebsocketUpgradePreservesTypedAuthenticationRejection() = runBlocking {
        assertAuthenticationUpgradeRejected(401)
    }

    @Test
    fun forbiddenWebsocketUpgradePreservesTypedAuthenticationRejection() = runBlocking {
        assertAuthenticationUpgradeRejected(403)
    }

    private suspend fun assertAuthenticationUpgradeRejected(status: Int) {
        server.enqueue(MockResponse.Builder().code(status).build())
        val gateway = gateway()

        val failure = runCatching { gateway.connect() }.exceptionOrNull()

        assertTrue(
            "Expected AuthenticationRejected for HTTP $status but received ${failure?.javaClass?.name}",
            failure is AuthenticationRejected,
        )
        assertEquals(
            GatewayConnectionState.Disconnected("Hermes rejected the dashboard credential."),
            gateway.state.value,
        )
    }

    @Test
    fun correlatesRpcResponsesAndPublishesStreamEvents() = runBlocking {
        server.enqueue(chatWebSocket())
        val gateway = gateway()
        gateway.connect()

        val resumed = gateway.resumeStoredSession("stored-42", "work", "android")
        assertEquals("runtime-7", resumed.runtimeSessionId)
        assertTrue(resumed.running == false)

        val eventCollector = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(5_000) { gateway.events.take(3).toList() }
        }
        val accepted = gateway.submitPrompt("runtime-7", "Hello from Celeste")
        val events = eventCollector.await()

        assertEquals("streaming", accepted.string("status"))
        assertEquals(listOf("message.start", "message.delta", "message.complete"), events.map { it.type })
        assertEquals("runtime-7", events.single { it.type == "message.delta" }.sessionId)
        assertEquals("hello", events.single { it.type == "message.delta" }.payload.string("text"))
        gateway.close()
    }

    @Test
    fun attachmentOnlyPromptAllowsEmptyText() = runBlocking {
        server.enqueue(chatWebSocket())
        val gateway = gateway()
        gateway.connect()

        val accepted = gateway.submitPrompt("runtime-7", "")

        assertEquals("streaming", accepted.string("status"))
        gateway.close()
    }

    @Test
    fun imageDetachRequiresConfirmedStatus() = runBlocking {
        server.enqueue(chatWebSocket())
        val gateway = gateway()
        gateway.connect()

        val failure = runCatching {
            gateway.detachImage("runtime-7", "/tmp/photo.jpg")
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals("Hermes could not detach the staged image.", failure?.message)
        gateway.close()
    }

    @Test
    fun resumedHistoryUsesDurableAndFallbackMessageIdentities() {
        val messages = decodeGatewayMessages(
            Json.parseToJsonElement(
                """[{"row_id":41,"role":"user","text":"Earlier message"},{"role":"tool","name":"terminal","context":"Repeated output"},{"role":"tool","name":"terminal","context":"Repeated output"}]""",
            ).jsonArray,
        )

        assertEquals(listOf("row-41", "steps:resume-1"), messages.map { it.id })
        assertEquals(messages.size, messages.map { it.id }.toSet().size)
        assertEquals(listOf("resume-1:tool", "resume-2:tool"), messages.single { it.role == "steps" }.steps.map { it.id })
    }

    @Test
    fun resumedHistoryLiftsPersistedAttachmentDirectivesOutOfUserText() {
        val messages = decodeGatewayMessages(
            Json.parseToJsonElement(
                """[
                    {"row_id":1,"role":"user","text":"Summarize this\n@file:`attachments/report final.pdf`"},
                    {"row_id":2,"role":"user","text":"Compare these\n@image:`/tmp/cat photo.png`\n[screenshot]"},
                    {"row_id":3,"role":"user","text":"What do you see in this image?\n@image:/tmp/only.png"}
                ]""".trimIndent(),
            ).jsonArray,
        )

        assertEquals(
            listOf("Summarize this", "Compare these", "What do you see in this image?"),
            messages.map { it.text },
        )
        assertEquals(
            listOf(ComposerAttachmentKind.File, ComposerAttachmentKind.Image, ComposerAttachmentKind.Image),
            messages.map { it.attachments.single().kind },
        )
        assertEquals(
            listOf("report final.pdf", "cat photo.png", "only.png"),
            messages.map { it.attachments.single().name },
        )
    }

    @Test
    fun resumedHistoryPreservesOrdinaryUserWhitespace() {
        val messages = decodeGatewayMessages(
            Json.parseToJsonElement(
                """[{"row_id":4,"role":"user","text":"  indented\n\ntrailing  "}]""",
            ).jsonArray,
        )

        assertEquals("  indented\n\ntrailing  ", messages.single().text)
    }

    @Test
    fun resumedHistoryPreservesDistinctAttachmentsWithTheSameName() {
        val message = decodeGatewayMessages(
            Json.parseToJsonElement(
                """[{"row_id":5,"role":"user","text":"Compare these\n@image:/tmp/a/photo.jpg\n@image:/tmp/b/photo.jpg"}]""",
            ).jsonArray,
        ).single()

        assertEquals("Compare these", message.text)
        assertEquals(listOf("photo.jpg", "photo.jpg"), message.attachments.map { it.name })
    }

    @Test
    fun resumedHistoryRebuildsChangedFilesAndLatestTaskProgress() {
        val decoded = decodeGatewayConversation(
            Json.parseToJsonElement(
                """[
                    {"row_id":1,"role":"user","text":"Build the work surfaces"},
                    {"row_id":2,"role":"assistant","tool_calls":[{"id":"edit-a","function":{"name":"patch","arguments":"{\"path\":\"ui/ConversationScreen.kt\"}"}}]},
                    {"row_id":3,"role":"tool","tool_call_id":"edit-a","tool_name":"patch","content":"{\"diff\":\"--- a/ui/ConversationScreen.kt\\n+++ b/ui/ConversationScreen.kt\\n@@\\n-old\\n+new\"}"},
                    {"row_id":4,"role":"assistant","reasoning":"Check the task placement."},
                    {"row_id":5,"role":"assistant","tool_calls":[{"id":"edit-b","function":{"name":"write_file","arguments":"{\"path\":\"ui/WorkSurfaces.kt\"}"}}]},
                    {"row_id":6,"role":"tool","tool_call_id":"edit-b","tool_name":"write_file","content":"{\"bytes_written\":240}"},
                    {"row_id":7,"role":"assistant","tool_calls":[{"id":"todo-a","function":{"name":"todo","arguments":"{\"todos\":[{\"id\":\"design\",\"content\":\"Design\",\"status\":\"in_progress\"}]}"}}]},
                    {"row_id":8,"role":"tool","tool_call_id":"todo-a","tool_name":"todo","content":"{\"todos\":[{\"id\":\"design\",\"content\":\"Design\",\"status\":\"completed\"},{\"id\":\"build\",\"content\":\"Build\",\"status\":\"in_progress\"}]}"}
                ]""".trimIndent(),
            ).jsonArray,
        )

        assertEquals(listOf("user", "steps", "changes"), decoded.messages.map { it.role })
        val files = decoded.messages.single { it.role == "changes" }.changedFiles()
        assertEquals(listOf("ui/ConversationScreen.kt", "ui/WorkSurfaces.kt"), files.map { it.path })
        assertEquals(1, files.first().additions)
        assertEquals(1, files.first().removals)
        assertEquals(listOf("design", "build"), decoded.taskProgress?.items?.map { it.id })
        assertEquals(TaskItemStatus.Completed, decoded.taskProgress?.items?.first()?.status)
    }

    @Test
    fun resumedFileEditWithExplicitFalseErrorRemainsCompleted() {
        val decoded = decodeGatewayConversation(
            Json.parseToJsonElement(
                """[
                    {"role":"user","text":"Update the file"},
                    {"role":"assistant","tool_calls":[{"id":"edit-a","function":{"name":"write_file","arguments":"{\"path\":\"App.kt\"}"}}]},
                    {"role":"tool","tool_call_id":"edit-a","tool_name":"write_file","content":"{\"error\":false,\"bytes_written\":24}"}
                ]""".trimIndent(),
            ).jsonArray,
        )

        val file = decoded.messages.single { it.role == "changes" }.changedFiles().single()
        assertEquals(FileEditState.Completed, file.state)
    }

    @Test
    fun resumedProcessCompletionUsesTheSameCompactProjectionAsLiveEvents() {
        val completion = """[IMPORTANT: Background process proc_42 exited (exit code 1).
Command: ./gradlew test
Output:
One test failed
]""".trimIndent()
        val messages = decodeGatewayMessages(
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("row_id", 41)
                        put("role", "user")
                        put("text", completion)
                    },
                )
                add(
                    buildJsonObject {
                        put("row_id", 42)
                        put("role", "user")
                        put("text", completion)
                    },
                )
                add(
                    buildJsonObject {
                        put("row_id", 43)
                        put("role", "assistant")
                        put("text", "I found the failure.")
                    },
                )
            },
        )

        assertEquals(listOf("process", "assistant"), messages.map { it.role })
        val process = messages.first()
        assertEquals("process:proc_42", process.id)
        assertEquals(BackgroundProcessState.Failed, process.processResult?.state)
        assertEquals("./gradlew test", process.processResult?.command)
        assertEquals("One test failed", process.processResult?.output)
        assertEquals(1, process.processResult?.exitCode)
    }

    @Test
    fun persistedClarificationRestoresAsCompactQuestionAndAnswerContent() {
        val messages = decodeGatewayMessages(
            Json.parseToJsonElement(
                """[
                    {"row_id":41,"role":"user","text":"Help me choose"},
                    {"role":"tool","tool_call_id":"clarify-1","name":"clarify","result":"{\"question\":\"Which target?\",\"choices_offered\":[\"Staging\",\"Production\"],\"user_response\":\"Staging\"}"},
                    {"row_id":43,"role":"assistant","text":"Continuing with staging."}
                ]""".trimIndent(),
            ).jsonArray,
        )

        assertEquals(listOf("user", "clarification", "assistant"), messages.map { it.role })
        val clarification = messages[1]
        assertTrue(!clarification.pending)
        assertEquals("Which target?", clarification.clarification?.question)
        assertEquals("Staging", clarification.clarification?.answer)
        assertEquals("clarify:clarify-1", clarification.id)
    }

    @Test
    fun multiSelectClarificationPreservesTransportValuesAndCleansSettledLabels() {
        val pending = ConversationMessage(
            role = "clarification",
            text = "",
            id = "clarify:clarify-1",
            pending = true,
            clarification = ClarificationExchange(
                requestId = "request-1",
                question = "Which checks?",
                choices = listOf("Tests (Recommended)", "Docs"),
                multiSelect = true,
            ),
        )
        val encoded = encodeClarificationChoices(listOf("Tests (Recommended)", "Docs"))

        assertEquals("[\"Tests (Recommended)\",\"Docs\"]", encoded)
        val settled = settleClarificationLocally(
            messages = listOf(pending),
            messageId = pending.id!!,
            requestId = "request-1",
            answer = encoded,
        ).single()
        assertTrue(!settled.pending)
        assertEquals("Tests, Docs", settled.clarification?.answer)
    }

    @Test
    fun canonicalMessageDecoderHandlesContentAndToolAliases() {
        val messages = decodeGatewayMessages(
            Json.parseToJsonElement(
                """[
                    {"row_id":41,"role":"user","content":"Run the check"},
                    {"role":"assistant","text":"Checking"},
                    {"role":"tool","tool_name":"terminal","context":"Repeated output"},
                    {"role":"tool","name":"terminal","context":"Repeated output"},
                    {"id":"final","role":"assistant","content":"Done"}
                ]""".trimIndent(),
            ).jsonArray,
        )

        assertEquals(listOf("user", "assistant", "steps", "assistant"), messages.map { it.role })
        assertEquals(listOf("Run the check", "Checking", "", "Done"), messages.map { it.text })
        val steps = messages.single { it.role == "steps" }.steps
        assertEquals(listOf("terminal", "terminal"), steps.map { it.toolName })
        assertEquals(listOf("row-41", "resume-1", "steps:resume-2", "final"), messages.map { it.id })
    }

    @Test
    fun restoredCodexCommentaryBecomesAssistantProseAndLeavesGenuineReasoning() {
        val commentary = "Checking PR mergeability and reviews"
        val laterReasoning = "Verify the merged state after commentary."
        val messages = decodeGatewayMessages(
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("row_id", 1)
                        put("role", "user")
                        put("text", "Merge it")
                    },
                )
                add(
                    buildJsonObject {
                        put("row_id", 2)
                        put("role", "assistant")
                        put("reasoning", "Compare current state.\n\n$commentary\n\n$laterReasoning")
                        put(
                            "codex_message_items",
                            """[{"type":"message","role":"assistant","phase":"commentary","content":[{"type":"output_text","text":"$commentary"}]}]""",
                        )
                    },
                )
                add(
                    buildJsonObject {
                        put("row_id", 3)
                        put("role", "tool")
                        put("tool_call_id", "call-1")
                        put("name", "terminal")
                        put("context", "gh pr view 75")
                    },
                )
                add(
                    buildJsonObject {
                        put("row_id", 4)
                        put("role", "assistant")
                        put("text", "Merged.")
                    },
                )
            },
        )

        assertEquals(
            listOf("user", "steps", "assistant", "steps", "assistant"),
            messages.map { it.role },
        )
        assertEquals("Compare current state.", messages[1].steps.single().detail)
        assertEquals(commentary, messages[2].text)
        assertTrue(messages[2].interim)
        assertEquals(
            listOf(ConversationStepKind.Reasoning, ConversationStepKind.Tool),
            messages[3].steps.map { it.kind },
        )
        assertEquals(laterReasoning, messages[3].steps[0].detail)
        assertEquals("terminal", messages[3].steps[1].toolName)
        assertTrue(messages.filter { it.role == "steps" }.flatMap { it.steps }.none { it.pending })
    }

    @Test
    fun restoredChangesStayAtTheEndWithoutReorderingCommentaryAndLaterActivity() {
        val messages = decodeGatewayMessages(
            Json.parseToJsonElement(
                """[
                    {"row_id":1,"role":"user","text":"Fix the queue"},
                    {"row_id":2,"role":"assistant","reasoning":"Plan the edit.","tool_calls":[{"id":"edit-a","function":{"name":"patch","arguments":"{\"path\":\"App.kt\"}"}}]},
                    {"row_id":3,"role":"tool","tool_call_id":"edit-a","tool_name":"patch","content":"{\"diff\":\"a/App.kt → b/App.kt\\n@@\\n-old\\n+new\"}"},
                    {"row_id":4,"role":"assistant","reasoning":"Check the first result.\n\nThe edit is in; I’m checking it.\n\nRun the focused tests.","codex_message_items":[{"type":"message","role":"assistant","phase":"commentary","content":[{"type":"output_text","text":"The edit is in; I’m checking it."}]}],"tool_calls":[{"id":"test-a","function":{"name":"terminal","arguments":"{\"command\":\"./gradlew focusedTest\"}"}}]},
                    {"row_id":5,"role":"tool","tool_call_id":"test-a","tool_name":"terminal","content":"Tests passed"},
                    {"row_id":6,"role":"assistant","reasoning":"Verify the diff.","tool_calls":[{"id":"read-a","function":{"name":"read_file","arguments":"{\"path\":\"App.kt\"}"}}]},
                    {"row_id":7,"role":"tool","tool_call_id":"read-a","tool_name":"read_file","content":"Read App.kt"},
                    {"row_id":8,"role":"assistant","text":"Everything checks out."}
                ]""".trimIndent(),
            ).jsonArray,
        )

        assertEquals(
            listOf("user", "steps", "assistant", "steps", "assistant", "changes"),
            messages.map { it.role },
        )
        assertEquals("The edit is in; I’m checking it.", messages[2].text)
        assertEquals("Everything checks out.", messages[4].text)
        val thinking = messages.filter { it.role == "steps" }
        assertEquals(2, thinking.size)
        assertEquals(
            listOf(ConversationStepKind.Reasoning, ConversationStepKind.Tool, ConversationStepKind.Reasoning, ConversationStepKind.Tool),
            thinking[1].steps.map { it.kind },
        )
        assertEquals(listOf("App.kt"), messages.last().changedFiles().map { it.path })
    }

    @Test
    fun restoredCodexSidecarOnlyProjectsCommentaryPhase() {
        val messages = decodeGatewayMessages(
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("row_id", 1)
                        put("role", "user")
                        put("text", "Inspect this")
                    },
                )
                add(
                    buildJsonObject {
                        put("row_id", 2)
                        put("role", "assistant")
                        put("reasoning", "Genuine reasoning remains.")
                        put(
                            "codex_message_items",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("type", "message")
                                        put("role", "assistant")
                                        put("phase", "commentary")
                                        put(
                                            "content",
                                            buildJsonArray {
                                                add(
                                                    buildJsonObject {
                                                        put("type", "output_text")
                                                        put("text", "Reading the current implementation.")
                                                    },
                                                )
                                            },
                                        )
                                    },
                                )
                                add(
                                    buildJsonObject {
                                        put("type", "message")
                                        put("role", "assistant")
                                        put("phase", "analysis")
                                        put(
                                            "content",
                                            buildJsonArray {
                                                add(
                                                    buildJsonObject {
                                                        put("type", "output_text")
                                                        put("text", "Provider scratchpad")
                                                    },
                                                )
                                            },
                                        )
                                    },
                                )
                                add(
                                    buildJsonObject {
                                        put("type", "message")
                                        put("role", "assistant")
                                        put("phase", "final_answer")
                                        put(
                                            "content",
                                            buildJsonArray {
                                                add(
                                                    buildJsonObject {
                                                        put("type", "output_text")
                                                        put("text", "The final answer")
                                                    },
                                                )
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )

        assertEquals(listOf("user", "steps", "assistant"), messages.map { it.role })
        assertEquals("Genuine reasoning remains.", messages[1].steps.single().detail)
        assertEquals("Reading the current implementation.", messages[2].text)
    }

    @Test
    fun missingAndMalformedCodexSidecarsLeaveOtherModelsUnchanged() {
        fun transcript(sidecar: String?): JsonArray = buildJsonArray {
            add(
                buildJsonObject {
                    put("row_id", 1)
                    put("role", "user")
                    put("text", "Inspect this")
                },
            )
            add(
                buildJsonObject {
                    put("row_id", 2)
                    put("role", "assistant")
                    put("reasoning", "Current provider reasoning.")
                    put("text", "Current provider answer.")
                    if (sidecar != null) put("codex_message_items", sidecar)
                },
            )
        }

        val existingProjection = decodeGatewayMessages(transcript(sidecar = null))
        val malformedProjection = decodeGatewayMessages(transcript(sidecar = "not-json"))

        assertEquals(existingProjection, malformedProjection)
        assertEquals(listOf("user", "steps", "assistant"), malformedProjection.map { it.role })
        assertEquals("Current provider reasoning.", malformedProjection[1].steps.single().detail)
        assertEquals("Current provider answer.", malformedProjection[2].text)
    }

    @Test
    fun resumedReasoningAndToolsShareOneChronologicalStepsProjection() {
        val messages = decodeGatewayMessages(
            Json.parseToJsonElement(
                """[
                    {"row_id":1,"role":"user","text":"Inspect this"},
                    {"row_id":2,"role":"assistant","reasoning_content":"First I should inspect the file.","text":""},
                    {"row_id":3,"role":"tool","tool_call_id":"call-1","name":"read_file","context":"app/Main.kt"},
                    {"row_id":4,"role":"assistant","reasoning":"The file confirms the UI boundary.","text":""},
                    {"row_id":5,"role":"assistant","text":"Done"}
                ]""".trimIndent(),
            ).jsonArray,
        )

        assertEquals(listOf("user", "steps", "assistant"), messages.map { it.role })
        val steps = messages.single { it.role == "steps" }.steps
        assertEquals(
            listOf(ConversationStepKind.Reasoning, ConversationStepKind.Tool, ConversationStepKind.Reasoning),
            steps.map { it.kind },
        )
        assertEquals("First I should inspect the file.", steps[0].detail)
        assertEquals("app/Main.kt", steps[1].context)
        assertEquals("The file confirms the UI boundary.", steps[2].detail)
        assertEquals("call-1", steps[1].id)
        assertTrue(steps.none { it.pending })
    }

    @Test
    fun resumedAssistantMessageStartsANewActivityCapsuleForLaterReasoning() {
        val messages = decodeGatewayMessages(
            Json.parseToJsonElement(
                """[
                    {"row_id":1,"role":"user","text":"Inspect this"},
                    {"row_id":2,"role":"assistant","reasoning":"First thought.","text":""},
                    {"row_id":3,"role":"tool","tool_call_id":"call-1","name":"read_file","context":"app/Main.kt"},
                    {"row_id":4,"role":"assistant","text":"I checked the first part."},
                    {"row_id":5,"role":"assistant","reasoning":"Second thought.","text":""},
                    {"row_id":6,"role":"assistant","text":"Finished."}
                ]""".trimIndent(),
            ).jsonArray,
        )

        assertEquals(
            listOf("user", "steps", "assistant", "steps", "assistant"),
            messages.map { it.role },
        )
        assertEquals("First thought.", messages[1].steps.first().detail)
        assertEquals("I checked the first part.", messages[2].text)
        assertEquals("Second thought.", messages[3].steps.single().detail)
        assertTrue(messages.filter { it.role == "steps" }.flatMap { it.steps }.none { it.pending })
    }

    @Test
    fun resumedHistorySafelySkipsStructuredReasoningDetails() {
        val messages = decodeGatewayMessages(
            Json.parseToJsonElement(
                """[
                    {"row_id":1,"role":"user","text":"Inspect this"},
                    {"row_id":2,"role":"assistant","reasoning_details":[{"type":"summary","text":"Internal metadata"}],"text":"Checking"},
                    {"row_id":3,"role":"tool","tool_call_id":"call-1","name":"read_file","context":"app/Main.kt"},
                    {"row_id":4,"role":"assistant","text":"Done"}
                ]""".trimIndent(),
            ).jsonArray,
        )

        assertEquals(listOf("user", "assistant", "steps", "assistant"), messages.map { it.role })
        assertEquals(listOf("Inspect this", "Checking", "", "Done"), messages.map { it.text })
        val step = messages.single { it.role == "steps" }.steps.single()
        assertEquals(ConversationStepKind.Tool, step.kind)
        assertEquals("app/Main.kt", step.context)
    }

    @Test
    fun resumedHistoryIgnoresMalformedAndBlankMessageIdentities() {
        val messages = decodeGatewayMessages(
            Json.parseToJsonElement(
                """[
                    {"row_id":{},"id":"legacy-id","role":"user","text":"One"},
                    {"row_id":[],"id":" ","message_id":7,"role":"assistant","text":"Two"},
                    {"row_id":null,"id":true,"role":"assistant","text":"Three"},
                    {"row_id":" ","id":null,"message_id":false,"role":"tool","text":"Four"}
                ]""".trimIndent(),
            ).jsonArray,
        )

        assertEquals(listOf("legacy-id", "7", "true", "steps:false"), messages.map { it.id })
    }

    @Test
    fun resumedHistoryDeduplicatesExplicitAndSyntheticIdentityCollisions() {
        val messages = decodeGatewayMessages(
            Json.parseToJsonElement(
                """[
                    {"id":"shared","role":"user","text":"One"},
                    {"message_id":"shared","role":"assistant","text":"Two"},
                    {"id":"resume-2","role":"assistant","text":"Three"},
                    {"role":"tool","text":"Four"}
                ]""".trimIndent(),
            ).jsonArray,
        )

        assertEquals(listOf("shared", "resume-1", "resume-2", "steps:resume-3"), messages.map { it.id })
        assertEquals(messages.size, messages.map { it.id }.toSet().size)
    }

    @Test
    fun reconnectRequestsAFreshEndpointAndFailsUnknownPendingRequests() = runBlocking {
        lateinit var firstServerSocket: WebSocket
        server.enqueue(
            MockResponse.Builder()
                .webSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            firstServerSocket = webSocket
                            webSocket.send(gatewayReadyFrame)
                        }
                    },
                )
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .webSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            webSocket.send(gatewayReadyFrame)
                        }

                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            webSocket.close(code, reason)
                        }
                    },
                )
                .build(),
        )
        val endpointCalls = AtomicInteger(0)
        val gateway = HermesGateway(
            httpClient = OkHttpClient(),
            json = Json { ignoreUnknownKeys = true },
            endpointProvider = {
                endpointCalls.incrementAndGet()
                server.url("/api/ws?ticket=ticket-${endpointCalls.get()}").toString()
            },
        )
        gateway.connect()

        val pending = async {
            runCatching { gateway.request("session.history") }.exceptionOrNull()
        }
        firstServerSocket.close(1012, "restart")
        withTimeout(5_000) {
            gateway.state.first { it is GatewayConnectionState.Disconnected }
        }
        assertTrue(pending.await() is IOException)

        gateway.connect()
        assertEquals(2, endpointCalls.get())
        assertEquals(GatewayConnectionState.Connected, gateway.state.value)
        val firstUpgrade = server.takeRequest()
        val secondUpgrade = server.takeRequest()
        assertEquals("ticket-1", firstUpgrade.url.queryParameter("ticket"))
        assertEquals("ticket-2", secondUpgrade.url.queryParameter("ticket"))
        gateway.close()
    }

    private fun gateway(): HermesGateway = HermesGateway(
        httpClient = OkHttpClient(),
        json = Json { ignoreUnknownKeys = true },
        endpointProvider = { server.url("/api/ws?ticket=single-use").toString() },
    )

    private fun chatWebSocket(): MockResponse =
        MockResponse.Builder()
            .webSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(gatewayReadyFrame)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val request = Json.parseToJsonElement(text).jsonObject
                        val id = request["id"].toString()
                        when (request["method"]?.jsonPrimitive?.content) {
                            "session.resume" -> webSocket.send(
                                """{"jsonrpc":"2.0","id":$id,"result":{"session_id":"runtime-7","resumed":"stored-42","running":false,"status":"idle","inflight":null,"messages":[{"id":"u1","role":"user","text":"Earlier message"}]}}""",
                            )

                            "image.detach" -> webSocket.send(
                                """{"jsonrpc":"2.0","id":$id,"result":{"detached":false,"count":1}}""",
                            )

                            "prompt.submit" -> {
                                webSocket.send(
                                    """{"jsonrpc":"2.0","method":"event","params":{"type":"message.start","session_id":"runtime-7","payload":{}}}""",
                                )
                                webSocket.send(
                                    """{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","session_id":"runtime-7","payload":{"text":"hello"}}}""",
                                )
                                webSocket.send(
                                    """{"jsonrpc":"2.0","method":"event","params":{"type":"message.complete","session_id":"runtime-7","payload":{"content":"hello","status":"complete"}}}""",
                                )
                                webSocket.send(
                                    """{"jsonrpc":"2.0","id":$id,"result":{"status":"streaming","session_id":"runtime-7"}}""",
                                )
                            }
                        }
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, reason)
                    }
                },
            )
            .build()

    private companion object {
        const val gatewayReadyFrame =
            """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{}}}"""
    }
}
