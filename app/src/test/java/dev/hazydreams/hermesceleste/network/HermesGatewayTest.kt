package dev.hazydreams.hermesceleste.network

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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

        val resumed = gateway.resumeStoredSession("stored-42", "android")
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
