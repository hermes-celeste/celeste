package dev.hazydreams.hermesceleste.network

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DashboardClientTest {
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
    fun readsDashboardStatusAndAuthenticationProviders() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"version":"1.2.3","auth_required":true}""")
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"providers":[{"name":"password","display_name":"Password","supports_password":true}]}""")
                .build(),
        )

        val result = DashboardClient().probe(server.url("/").toString())

        assertTrue(result.authRequired)
        assertEquals("1.2.3", result.version)
        assertEquals(1, result.providers.size)
        assertEquals("password", result.providers.single().id)
        assertTrue(result.supportsPassword)
        assertEquals("/api/status", server.takeRequest().url.encodedPath)
        assertEquals("/api/auth/providers", server.takeRequest().url.encodedPath)
    }

    @Test
    fun tokenDashboardSkipsProviderRequest() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"version":"1.2.3","auth_required":false}""")
                .build(),
        )

        val result = DashboardClient().probe(server.url("/").toString())

        assertTrue(!result.authRequired)
        assertTrue(result.providers.isEmpty())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun listsTheDashboardStoredSessionsOverJsonRpc() = runBlocking {
        server.enqueue(sessionListWebSocket())

        val sessions = DashboardClient().listSessions(
            baseUrl = server.url("/").toString().trimEnd('/'),
            credential = GatewayCredential.StaticToken("private-token"),
        )

        assertEquals(2, sessions.size)
        assertEquals("This conversation", sessions.first().title)
        assertEquals("desktop", sessions.first().source)
        assertEquals("work", sessions.first().profile)
        val upgrade = server.takeRequest()
        assertEquals("/api/ws", upgrade.url.encodedPath)
        assertEquals("private-token", upgrade.url.queryParameter("token"))
    }

    @Test
    fun passwordSessionMintsOneTimeTicketBeforeListing() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Set-Cookie", "hermes_session_at=access; Path=/; HttpOnly")
                .body("""{"ok":true,"next":"/"}""")
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"ticket":"single-use-ticket","ttl_seconds":30}""")
                .build(),
        )
        server.enqueue(sessionListWebSocket())
        val client = DashboardClient()
        val baseUrl = server.url("/").toString().trimEnd('/')

        client.passwordLogin(baseUrl, "password", "test-user", "not-logged")
        val sessions = client.listSessions(baseUrl, GatewayCredential.CookieSession)

        assertEquals(2, sessions.size)
        val login = server.takeRequest()
        val ticket = server.takeRequest()
        val upgrade = server.takeRequest()
        assertEquals("/auth/password-login", login.url.encodedPath)
        assertTrue(login.body?.utf8().orEmpty().contains("\"username\":\"test-user\""))
        assertEquals("hermes_session_at=access", ticket.headers["Cookie"])
        assertEquals("single-use-ticket", upgrade.url.queryParameter("ticket"))
    }

    @Test
    fun resumesTheSelectedStoredConversation() = runBlocking {
        server.enqueue(sessionResumeWebSocket())
        val baseUrl = server.url("/").toString().trimEnd('/')

        val resumed = DashboardClient().resumeSession(
            baseUrl = baseUrl,
            credential = GatewayCredential.StaticToken("private-token"),
            storedSessionId = "stored-42",
        )

        assertEquals("runtime-7", resumed.runtimeSessionId)
        assertEquals("stored-42", resumed.storedSessionId)
        assertEquals(listOf("user", "assistant"), resumed.messages.map { it.role })
        assertEquals("perfect. lets build that.", resumed.messages.first().text)
    }

    @Test
    fun listsProfilesWithTheOfficialStaticTokenHeader() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """{"profiles":[{"name":"default","is_default":true,"model":"hermes-4","provider":"nous"},{"name":"work","is_default":false,"model":null,"provider":null}]}""",
                )
                .build(),
        )
        val baseUrl = server.url("/").toString().trimEnd('/')

        val profiles = DashboardClient().listProfiles(
            baseUrl = baseUrl,
            credential = GatewayCredential.StaticToken("profile-session-token"),
        )

        assertEquals(listOf("default", "work"), profiles.map { it.name })
        assertTrue(profiles.first().isDefault)
        assertEquals("hermes-4", profiles.first().model)
        val request = server.takeRequest()
        assertEquals("/api/profiles", request.url.encodedPath)
        assertEquals("profile-session-token", request.headers["X-Hermes-Session-Token"])
    }

    @Test
    fun persistentGatewayMintsAFreshCookieTicketForEveryConnection() = runBlocking {
        lateinit var firstServerSocket: WebSocket
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Set-Cookie", "hermes_session_at=access; Path=/; HttpOnly")
                .body("""{"ok":true,"next":"/"}""")
                .build(),
        )
        server.enqueue(
            MockResponse.Builder().code(200)
                .body("""{"ticket":"ticket-one","ttl_seconds":30}""")
                .build(),
        )
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
            MockResponse.Builder().code(200)
                .body("""{"ticket":"ticket-two","ttl_seconds":30}""")
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
        val client = DashboardClient()
        val baseUrl = server.url("/").toString().trimEnd('/')
        client.passwordLogin(baseUrl, "password", "test-user", "not-logged")
        val gateway = client.createGateway(baseUrl, GatewayCredential.CookieSession)

        gateway.connect()
        firstServerSocket.close(1012, "restart")
        withTimeout(5_000) {
            gateway.state.first { it is GatewayConnectionState.Disconnected }
        }
        gateway.connect()
        gateway.close()

        val requests = List(5) { server.takeRequest() }
        assertEquals("/api/auth/ws-ticket", requests[1].url.encodedPath)
        assertEquals("ticket-one", requests[2].url.queryParameter("ticket"))
        assertEquals("/api/auth/ws-ticket", requests[3].url.encodedPath)
        assertEquals("ticket-two", requests[4].url.queryParameter("ticket"))
    }

    private fun sessionListWebSocket(): MockResponse =
        MockResponse.Builder()
            .webSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{}}}""")
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val request = Json.parseToJsonElement(text).jsonObject
                        assertEquals("session.list", request["method"]?.jsonPrimitive?.content)
                        webSocket.send(
                            """{"jsonrpc":"2.0","id":"session-list","result":{"sessions":[{"id":"s1","title":"This conversation","preview":"perfect. lets build that.","started_at":123.5,"message_count":42,"source":"desktop","profile_name":"work"},{"id":"s2","title":"Older chat","preview":"hello","started_at":100,"message_count":2,"source":"cli"}]}}""",
                        )
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, reason)
                    }
                },
            )
            .build()

    private fun sessionResumeWebSocket(): MockResponse =
        MockResponse.Builder()
            .webSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val request = Json.parseToJsonElement(text).jsonObject
                        assertEquals("session.resume", request["method"]?.jsonPrimitive?.content)
                        assertEquals(
                            "stored-42",
                            request["params"]?.jsonObject?.get("session_id")?.jsonPrimitive?.content,
                        )
                        webSocket.send(
                            """{"jsonrpc":"2.0","id":"session-resume","result":{"session_id":"runtime-7","resumed":"stored-42","messages":[{"role":"user","text":"perfect. lets build that."},{"role":"assistant","text":"on it."}]}}""",
                        )
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
