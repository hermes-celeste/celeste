package dev.hazydreams.hermesceleste.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
    fun malformedStatusResponseIsNotClassifiedAsAuthenticationRejection() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("[]").build())

        val error = runCatching {
            DashboardClient().probe(server.url("/").toString())
        }.exceptionOrNull()

        assertTrue(error is InvalidDashboardResponse)
    }

    @Test
    fun listsDashboardSessionsFromTheAuthoritativeRestCatalog() = runTest {
        server.enqueue(sessionListRest())

        val page = DashboardClient().listSessions(
            baseUrl = server.url("/").toString().trimEnd('/'),
            credential = GatewayCredential.StaticToken("private-token"),
        )

        val sessions = page.sessions
        assertEquals(3, sessions.size)
        assertEquals(3, page.total)
        assertEquals(15, page.limit)
        assertEquals(0, page.offset)
        assertFalse(page.hasMore)
        assertEquals("This conversation", sessions.first().title)
        assertEquals("desktop", sessions.first().source)
        assertEquals("work", sessions.first().profile)
        assertEquals("hermes-4", sessions.first().model)
        assertEquals(true, sessions.first().pinned)
        assertTrue(sessions.first().unread)
        assertEquals(456.75, sessions.first().lastActiveAt, 0.0)
        assertEquals(100.0, sessions[1].lastActiveAt, 0.0)
        assertEquals("cron", sessions.last().source)
        assertEquals(false, sessions.last().pinned)
        val request = server.takeRequest()
        assertEquals("/api/sessions", request.url.encodedPath)
        assertEquals("15", request.url.queryParameter("limit"))
        assertEquals("0", request.url.queryParameter("offset"))
        assertEquals("0", request.url.queryParameter("min_messages"))
        assertEquals("exclude", request.url.queryParameter("archived"))
        assertEquals("recent", request.url.queryParameter("order"))
        assertEquals("private-token", request.headers["X-Hermes-Session-Token"])
    }

    @Test
    fun searchesFullSessionHistoryAndStripsFtsMarkers() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """{"results":[{"session_id":"older-notes","title":"Dashboard connection notes","snippet":"Before >>>connection<<< after","session_started":100.5,"last_active":456.75,"message_count":8,"source":"desktop","model":"hermes-4"}]}""",
                )
                .build(),
        )

        val results = DashboardClient().searchSessions(
            baseUrl = server.url("/").toString().trimEnd('/'),
            credential = GatewayCredential.StaticToken("private-token"),
            query = "  connection notes  ",
            profile = "work",
        )

        val session = results.single()
        assertEquals("older-notes", session.id)
        assertEquals("Dashboard connection notes", session.title)
        assertEquals("Before connection after", session.preview)
        assertEquals("work", session.profile)
        assertEquals("desktop", session.source)
        assertEquals("hermes-4", session.model)
        assertEquals(8, session.messageCount)
        assertEquals(null, session.pinned)
        assertFalse(session.unread)
        assertEquals(456.75, session.lastActiveAt, 0.0)

        val request = server.takeRequest()
        assertEquals("/api/sessions/search", request.url.encodedPath)
        assertEquals("connection notes", request.url.queryParameter("q"))
        assertEquals("20", request.url.queryParameter("limit"))
        assertEquals("work", request.url.queryParameter("profile"))
        assertEquals("private-token", request.headers["X-Hermes-Session-Token"])
    }

    @Test
    fun capsSessionDiscoveryAtFifty() = runTest {
        server.enqueue(sessionListRest(limit = 50))

        DashboardClient().listSessions(
            baseUrl = server.url("/").toString().trimEnd('/'),
            credential = GatewayCredential.None,
            limit = 500,
        )

        assertEquals("50", server.takeRequest().url.queryParameter("limit"))
    }

    @Test
    fun requestsTheSelectedCatalogOffsetAndPreservesServerPagingMetadata() = runTest {
        server.enqueue(
            sessionListRest(
                total = 41,
                limit = 15,
                offset = 15,
            ),
        )

        val page = DashboardClient().listSessions(
            baseUrl = server.url("/").toString().trimEnd('/'),
            credential = GatewayCredential.None,
            limit = 15,
            offset = 15,
        )

        assertEquals(41, page.total)
        assertEquals(15, page.limit)
        assertEquals(15, page.offset)
        assertTrue(page.hasMore)
        val request = server.takeRequest()
        assertEquals("15", request.url.queryParameter("limit"))
        assertEquals("15", request.url.queryParameter("offset"))
    }

    @Test
    fun rejectsPagingMetadataForADifferentWindow() = runTest {
        server.enqueue(
            sessionListRest(
                total = 41,
                limit = 15,
                offset = 0,
            ),
        )

        val failure = runCatching {
            DashboardClient().listSessions(
                baseUrl = server.url("/").toString().trimEnd('/'),
                credential = GatewayCredential.None,
                limit = 15,
                offset = 15,
            )
        }.exceptionOrNull()

        assertTrue(failure is InvalidDashboardResponse)
    }

    @Test
    fun marksAStoredSessionReadWithItsProfileContext() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("""{"ok":true}""").build())
        val baseUrl = server.url("/").toString().trimEnd('/')

        DashboardClient().markSessionRead(
            baseUrl = baseUrl,
            credential = GatewayCredential.StaticToken("private-token"),
            sessionId = "stored/session 42",
            profile = "work",
        )

        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/api/sessions/stored%2Fsession%2042", request.url.encodedPath)
        assertEquals("private-token", request.headers["X-Hermes-Session-Token"])
        assertEquals(
            Json.parseToJsonElement("""{"unread":false,"profile":"work"}"""),
            Json.parseToJsonElement(request.body?.utf8().orEmpty()),
        )
    }

    @Test
    fun pinsAStoredSessionWithItsProfileAndUsesTheServerValue() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("""{"ok":true,"title":"Notes","pinned":true}""").build())
        val baseUrl = server.url("/").toString().trimEnd('/')

        val pinned = DashboardClient().setSessionPinned(
            baseUrl = baseUrl,
            credential = GatewayCredential.StaticToken("private-token"),
            sessionId = "stored/session 42",
            profile = "work",
            pinned = true,
        )

        assertTrue(pinned)
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/api/sessions/stored%2Fsession%2042", request.url.encodedPath)
        assertEquals(
            Json.parseToJsonElement("""{"pinned":true,"profile":"work"}"""),
            Json.parseToJsonElement(request.body?.utf8().orEmpty()),
        )
    }

    @Test
    fun renamesAStoredSessionWithItsProfileAndUsesTheServerTitle() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("""{"ok":true,"title":"Connection notes"}""").build())
        val baseUrl = server.url("/").toString().trimEnd('/')

        val title = DashboardClient().renameSession(
            baseUrl = baseUrl,
            credential = GatewayCredential.None,
            sessionId = "stored-42",
            profile = "default",
            title = "Connection notes",
        )

        assertEquals("Connection notes", title)
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals(
            Json.parseToJsonElement("""{"title":"Connection notes","profile":"default"}"""),
            Json.parseToJsonElement(request.body?.utf8().orEmpty()),
        )
    }

    @Test
    fun passwordSessionUsesItsCookieForRestSessionDiscovery() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Set-Cookie", "hermes_session_at=access; Path=/; HttpOnly")
                .body("""{"ok":true,"next":"/"}""")
                .build(),
        )
        server.enqueue(sessionListRest())
        val client = DashboardClient()
        val baseUrl = server.url("/").toString().trimEnd('/')

        client.passwordLogin(baseUrl, "password", "test-user", "not-logged")
        val sessions = client.listSessions(baseUrl, GatewayCredential.CookieSession).sessions

        assertEquals(3, sessions.size)
        val login = server.takeRequest()
        val listing = server.takeRequest()
        assertEquals("/auth/password-login", login.url.encodedPath)
        assertTrue(login.body?.utf8().orEmpty().contains("\"username\":\"test-user\""))
        assertEquals("/api/sessions", listing.url.encodedPath)
        assertEquals("hermes_session_at=access", listing.headers["Cookie"])
    }

    @Test
    fun missingSessionCatalogIsAnIncompatibleDashboard() = runBlocking {
        server.enqueue(MockResponse.Builder().code(404).body("not found").build())

        val failure = runCatching {
            DashboardClient().listSessions(
                baseUrl = server.url("/").toString().trimEnd('/'),
                credential = GatewayCredential.StaticToken("private-token"),
            )
        }.exceptionOrNull()

        assertTrue(failure is TransportUnavailable)
        assertEquals(1, server.requestCount)
        assertEquals("/api/sessions", server.takeRequest().url.encodedPath)
    }

    @Test
    fun sessionCatalogFailuresRemainFailures() {
        val baseUrl = server.url("/").toString().trimEnd('/')
        listOf(
            401 to AuthenticationRejected::class.java,
            403 to AuthenticationRejected::class.java,
            429 to RateLimited::class.java,
            500 to TransportUnavailable::class.java,
        ).forEach { (status, expectedType) ->
            val before = server.requestCount
            server.enqueue(MockResponse.Builder().code(status).build())

            val failure = runCatching {
                runBlocking { DashboardClient().listSessions(baseUrl, GatewayCredential.None) }
            }.exceptionOrNull()

            assertTrue("Expected ${expectedType.simpleName} for HTTP $status", expectedType.isInstance(failure))
            assertEquals(before + 1, server.requestCount)
        }

        val before = server.requestCount
        server.enqueue(MockResponse.Builder().code(200).body("[]").build())
        assertThrows(InvalidDashboardResponse::class.java) {
            runBlocking { DashboardClient().listSessions(baseUrl, GatewayCredential.None) }
        }
        assertEquals(before + 1, server.requestCount)

        val beforeInvalidRow = server.requestCount
        server.enqueue(MockResponse.Builder().code(200).body("""{"sessions":[{"title":"missing id"}]}""").build())
        assertThrows(InvalidDashboardResponse::class.java) {
            runBlocking { DashboardClient().listSessions(baseUrl, GatewayCredential.None) }
        }
        assertEquals(beforeInvalidRow + 1, server.requestCount)
    }

    @Test
    fun exportsAndRestoresOnlyOriginBoundHermesSessionCookies() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Set-Cookie", "hermes_session_at=synthetic-access; Path=/; Max-Age=900; HttpOnly")
                .addHeader("Set-Cookie", "hermes_session_rt=synthetic-refresh; Path=/; Max-Age=86400; HttpOnly")
                .addHeader("Set-Cookie", "hermes_session_pkce=must-not-persist; Path=/; Max-Age=600; HttpOnly")
                .body("""{"ok":true,"next":"/"}""")
                .build(),
        )
        server.enqueue(MockResponse.Builder().code(200).body("""{"profiles":[]}""").build())
        val baseUrl = server.url("/").toString().trimEnd('/')
        val signedIn = DashboardClient()
        signedIn.passwordLogin(baseUrl, "password", "test-user", "synthetic-password")

        val material = requireNotNull(signedIn.exportAuthentication(baseUrl))
        val restored = DashboardClient()

        assertEquals("[REDACTED]", material.toString())
        assertTrue(restored.restoreAuthentication(baseUrl, material))
        assertTrue(!restored.restoreAuthentication("https://other.example.net", material))
        assertTrue(restored.restoreAuthentication(baseUrl, material))
        restored.listProfiles(baseUrl, GatewayCredential.CookieSession)

        server.takeRequest()
        val profiles = server.takeRequest()
        val cookieHeader = profiles.headers["Cookie"].orEmpty()
        assertTrue(cookieHeader.contains("hermes_session_at=synthetic-access"))
        assertTrue(cookieHeader.contains("hermes_session_rt=synthetic-refresh"))
        assertTrue(!cookieHeader.contains("hermes_session_pkce"))
    }

    @Test
    fun remembersCookiesScopedBelowADashboardPathPrefix() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader(
                    "Set-Cookie",
                    "hermes_session_rt=prefix-refresh; Path=/prefix/; Max-Age=86400; HttpOnly",
                )
                .body("""{"ok":true,"next":"/prefix/"}""")
                .build(),
        )
        server.enqueue(MockResponse.Builder().code(200).body("""{"profiles":[]}""").build())
        val baseUrl = server.url("/prefix").toString().trimEnd('/')
        val signedIn = DashboardClient()
        signedIn.passwordLogin(baseUrl, "password", "test-user", "synthetic-password")

        val material = requireNotNull(signedIn.exportAuthentication(baseUrl))
        val restored = DashboardClient()
        assertTrue(restored.restoreAuthentication(baseUrl, material))
        restored.listProfiles(baseUrl, GatewayCredential.CookieSession)

        server.takeRequest()
        val profiles = server.takeRequest()
        assertEquals("/prefix/api/profiles", profiles.url.encodedPath)
        assertEquals("hermes_session_rt=prefix-refresh", profiles.headers["Cookie"])
    }

    @Test
    fun restoresValidRefreshCookieAfterAccessCookieExpiry() = runTest {
        val now = System.currentTimeMillis()
        val host = server.url("/").host
        val material = AuthenticationMaterial(
            """[
                {"name":"hermes_session_at","value":"expired-access","expiresAt":${now - 1},"domain":"$host","path":"/","secure":false,"httpOnly":true,"hostOnly":true},
                {"name":"hermes_session_rt","value":"valid-refresh","expiresAt":${now + 60_000},"domain":"$host","path":"/","secure":false,"httpOnly":true,"hostOnly":true}
            ]""".trimIndent(),
        )
        server.enqueue(MockResponse.Builder().code(200).body("""{"profiles":[]}""").build())
        val baseUrl = server.url("/").toString().trimEnd('/')
        val restored = DashboardClient()

        assertTrue(restored.restoreAuthentication(baseUrl, material))
        restored.listProfiles(baseUrl, GatewayCredential.CookieSession)

        val profiles = server.takeRequest()
        assertEquals("hermes_session_rt=valid-refresh", profiles.headers["Cookie"])
    }

    @Test
    fun providerHintAloneCannotRestoreAuthentication() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Set-Cookie", "hermes_session_provider=password; Path=/; Max-Age=86400; HttpOnly")
                .body("""{"ok":true,"next":"/"}""")
                .build(),
        )
        val baseUrl = server.url("/").toString().trimEnd('/')
        val signedIn = DashboardClient()
        signedIn.passwordLogin(baseUrl, "password", "test-user", "synthetic-password")
        val material = requireNotNull(signedIn.exportAuthentication(baseUrl))

        assertFalse(DashboardClient().restoreAuthentication(baseUrl, material))
        assertEquals("StaticToken([REDACTED])", GatewayCredential.StaticToken("synthetic-token").toString())
    }

    @Test
    fun newPasswordLoginReplacesObsoleteCookieAuthentication() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Set-Cookie", "hermes_session_at=obsolete; Path=/; Max-Age=900; HttpOnly")
                .body("""{"ok":true,"next":"/"}""")
                .build(),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Set-Cookie", "hermes_session_at=current; Path=/; Max-Age=900; HttpOnly")
                .body("""{"ok":true,"next":"/"}""")
                .build(),
        )
        server.enqueue(MockResponse.Builder().code(200).body("""{"profiles":[]}""").build())
        val baseUrl = server.url("/").toString().trimEnd('/')
        val client = DashboardClient()

        client.passwordLogin(baseUrl, "password", "old-user", "synthetic-password")
        client.passwordLogin(baseUrl, "password", "new-user", "synthetic-password")
        client.listProfiles(baseUrl, GatewayCredential.CookieSession)

        server.takeRequest()
        val replacementLogin = server.takeRequest()
        val profiles = server.takeRequest()
        assertEquals(null, replacementLogin.headers["Cookie"])
        assertEquals("hermes_session_at=current", profiles.headers["Cookie"])
    }

    @Test
    fun classifiesAuthenticationRejectionAndRateLimiting() {
        val client = DashboardClient()
        val baseUrl = server.url("/").toString().trimEnd('/')
        server.enqueue(MockResponse.Builder().code(401).body("unauthorized").build())

        assertThrows(AuthenticationRejected::class.java) {
            runBlocking { client.passwordLogin(baseUrl, "password", "test-user", "synthetic-password") }
        }

        server.enqueue(MockResponse.Builder().code(429).body("slow down").build())
        assertThrows(RateLimited::class.java) {
            runBlocking { client.passwordLogin(baseUrl, "password", "test-user", "synthetic-password") }
        }
    }

    @Test
    fun logoutAcceptsHermesRedirectAndClearsThePrivateCookieJar() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Set-Cookie", "hermes_session_at=access; Path=/; HttpOnly")
                .body("""{"ok":true,"next":"/"}""")
                .build(),
        )
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/login").build())
        server.enqueue(MockResponse.Builder().code(200).body("""{"profiles":[]}""").build())
        val client = DashboardClient()
        val baseUrl = server.url("/").toString().trimEnd('/')

        client.passwordLogin(baseUrl, "password", "test-user", "synthetic-password")
        client.logout(baseUrl)
        client.clearAuthentication()
        client.listProfiles(baseUrl, GatewayCredential.CookieSession)

        server.takeRequest()
        val logout = server.takeRequest()
        val profiles = server.takeRequest()
        assertEquals("/auth/logout", logout.url.encodedPath)
        assertEquals("POST", logout.method)
        assertEquals("hermes_session_at=access", logout.headers["Cookie"])
        assertEquals(null, profiles.headers["Cookie"])
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
    fun disposableResumeUsesCanonicalGatewayMessageDecoding() = runBlocking {
        val transcript = """[
            {"row_id":41,"role":"user","content":"Run the check"},
            {"role":"assistant","text":"Checking"},
            {"role":"tool","tool_name":"terminal","context":"Repeated output"},
            {"role":"tool","name":"terminal","context":"Repeated output"},
            {"id":"final","role":"assistant","content":"Done"}
        ]""".trimIndent()
        server.enqueue(sessionResumeWebSocket(transcript))
        val baseUrl = server.url("/").toString().trimEnd('/')

        val disposable = DashboardClient().resumeSession(
            baseUrl = baseUrl,
            credential = GatewayCredential.None,
            storedSessionId = "stored-42",
        )
        val canonical = decodeGatewayMessages(Json.parseToJsonElement(transcript).jsonArray)

        assertEquals(canonical, disposable.messages)
        assertEquals(
            listOf("row-41", "resume-1", "steps:resume-2", "final"),
            disposable.messages.map { it.id },
        )
        val steps = disposable.messages.single { it.role == "steps" }.steps
        assertEquals(listOf("terminal", "terminal"), steps.map { it.toolName })
    }

    @Test
    fun loadsPersistedTranscriptWithReasoningAndCompactedHistory() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """{"session_id":"stored-42","messages":[{"id":1,"role":"user","content":"Inspect this"},{"id":2,"role":"assistant","content":"","reasoning":"I should read the file first.","tool_calls":[{"id":"call-1","function":{"name":"read_file","arguments":"{\"path\":\"README.md\"}"}}]},{"id":3,"role":"tool","content":"contents","tool_call_id":"call-1","tool_name":"read_file"},{"id":4,"role":"assistant","content":"Done"}],"pagination":{"limit":500,"offset":0,"order":"latest","returned":4}}""",
                )
                .build(),
        )

        val messages = DashboardClient().loadSessionMessages(
            baseUrl = server.url("/").toString().trimEnd('/'),
            credential = GatewayCredential.StaticToken("private-token"),
            sessionId = "stored-42",
            profile = "work",
        )

        assertEquals(listOf("user", "steps", "assistant"), messages.map { it.role })
        val steps = messages.single { it.role == "steps" }.steps
        assertEquals(
            listOf(ConversationStepKind.Reasoning, ConversationStepKind.Tool),
            steps.map { it.kind },
        )
        assertEquals("I should read the file first.", steps.first().detail)
        assertEquals("read_file", steps.last().toolName)
        assertEquals("{\"path\":\"README.md\"}", steps.last().context)
        assertEquals("contents", steps.last().result)
        val request = server.takeRequest()
        assertEquals("/api/sessions/stored-42/messages", request.url.encodedPath)
        assertEquals("work", request.url.queryParameter("profile"))
        assertEquals("500", request.url.queryParameter("limit"))
        assertEquals("latest", request.url.queryParameter("order"))
        assertEquals("true", request.url.queryParameter("include_compacted"))
        assertEquals("private-token", request.headers["X-Hermes-Session-Token"])
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
    fun missingProfileCatalogIsAnIncompatibleDashboard() = runTest {
        server.enqueue(MockResponse.Builder().code(404).body("not found").build())
        val baseUrl = server.url("/").toString().trimEnd('/')

        val failure = runCatching {
            DashboardClient().listProfiles(baseUrl, GatewayCredential.None)
        }.exceptionOrNull()

        assertTrue(failure is TransportUnavailable)
    }

    @Test
    fun profileCatalogFailuresAreNotConvertedToTheDefaultProfile() {
        val baseUrl = server.url("/").toString().trimEnd('/')
        listOf(
            401 to AuthenticationRejected::class.java,
            403 to AuthenticationRejected::class.java,
            429 to RateLimited::class.java,
            500 to TransportUnavailable::class.java,
        ).forEach { (status, expectedType) ->
            server.enqueue(MockResponse.Builder().code(status).build())

            val failure = runCatching {
                runBlocking { DashboardClient().listProfiles(baseUrl, GatewayCredential.None) }
            }.exceptionOrNull()

            assertTrue("Expected ${expectedType.simpleName} for HTTP $status", expectedType.isInstance(failure))
        }

        listOf("[]", """{"profiles":[{}]}""").forEach { malformedBody ->
            server.enqueue(MockResponse.Builder().code(200).body(malformedBody).build())
            assertThrows(InvalidDashboardResponse::class.java) {
                runBlocking { DashboardClient().listProfiles(baseUrl, GatewayCredential.None) }
            }
        }
    }

    @Test
    fun disposableRequestsIgnoreResponsesWithUnexpectedIds() = runBlocking {
        DisposableOperation.entries.forEach { operation ->
            server.enqueue(disposableWebSocket(operation) { webSocket ->
                webSocket.send(successFrame(operation, id = "another-request"))
                webSocket.send(successFrame(operation))
            })

            val result = operation.execute(DashboardClient(), server.url("/").toString().trimEnd('/'))

            assertEquals("runtime-7", (result as ResumedSession).runtimeSessionId)
        }
    }

    @Test
    fun cancellingDisposableRequestsCancelsTheirSockets() = runBlocking {
        DisposableOperation.entries.forEach { operation ->
            val requestReceived = CompletableDeferred<Unit>()
            val disconnected = CompletableDeferred<Unit>()
            server.enqueue(
                MockResponse.Builder()
                    .webSocketUpgrade(
                        object : WebSocketListener() {
                            override fun onMessage(webSocket: WebSocket, text: String) {
                                requestReceived.complete(Unit)
                            }

                            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                                disconnected.complete(Unit)
                            }

                            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                                disconnected.complete(Unit)
                            }
                        },
                    )
                    .build(),
            )
            val request = async {
                operation.execute(DashboardClient(), server.url("/").toString().trimEnd('/'))
            }

            withTimeout(5_000) { requestReceived.await() }
            request.cancelAndJoin()

            withTimeout(5_000) { disconnected.await() }
        }
    }

    @Test
    fun disposableRequestsClassifyUpgradeFailures() = runBlocking {
        val baseUrl = server.url("/").toString().trimEnd('/')
        DisposableOperation.entries.forEach { operation ->
            listOf(
                401 to AuthenticationRejected::class.java,
                403 to AuthenticationRejected::class.java,
                429 to RateLimited::class.java,
                500 to TransportUnavailable::class.java,
            ).forEach { (status, expectedType) ->
                server.enqueue(MockResponse.Builder().code(status).build())

                val failure = runCatching {
                    operation.execute(DashboardClient(), baseUrl)
                }.exceptionOrNull()

                assertTrue(
                    "Expected ${expectedType.simpleName} for ${operation.name} HTTP $status",
                    expectedType.isInstance(failure),
                )
                if (status == 500) assertEquals(operation.transportFailure, failure?.message)
            }
        }
    }

    @Test
    fun disposableRequestsRejectMalformedOperationResponses() = runBlocking {
        val baseUrl = server.url("/").toString().trimEnd('/')
        DisposableOperation.entries.forEach { operation ->
            server.enqueue(disposableWebSocket(operation) { webSocket ->
                webSocket.send(
                    """{"jsonrpc":"2.0","id":"${operation.requestId}","result":{}}""",
                )
            })

            val failure = runCatching {
                operation.execute(DashboardClient(), baseUrl)
            }.exceptionOrNull()

            assertTrue("Expected InvalidDashboardResponse for ${operation.name}", failure is InvalidDashboardResponse)
            assertEquals(operation.invalidResponse, failure?.message)
        }
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

    private fun sessionListRest(
        total: Int = 3,
        limit: Int = 15,
        offset: Int = 0,
    ): MockResponse =
        MockResponse.Builder()
            .code(200)
            .body(
                """{"sessions":[{"id":"s1","title":"This conversation","preview":"perfect. lets build that.","started_at":123.5,"last_active":456.75,"message_count":42,"source":"desktop","profile":"work","model":"hermes-4","pinned":true,"unread":true},{"id":"s2","title":"Older chat","preview":"hello","started_at":100,"message_count":2,"source":"cli","profile":"default","model":null,"pinned":false,"unread":false},{"id":"cron-1","title":"Morning brief","preview":"","started_at":90,"message_count":1,"source":"cron","profile":"default","model":"hermes-4","pinned":false,"unread":false}],"total":$total,"limit":$limit,"offset":$offset}""",
            )
            .build()


    private fun sessionResumeWebSocket(
        messages: String =
            """[{"role":"user","text":"perfect. lets build that."},{"role":"assistant","text":"on it."}]""",
    ): MockResponse =
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
                            """{"jsonrpc":"2.0","id":"session-resume","result":{"session_id":"runtime-7","resumed":"stored-42","messages":$messages}}""",
                        )
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, reason)
                    }
                },
            )
            .build()

    private fun disposableWebSocket(
        operation: DisposableOperation,
        respond: (WebSocket) -> Unit,
    ): MockResponse = MockResponse.Builder()
        .webSocketUpgrade(
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val request = Json.parseToJsonElement(text).jsonObject
                    assertEquals(operation.method, request["method"]?.jsonPrimitive?.content)
                    respond(webSocket)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }
            },
        )
        .build()

    private fun successFrame(operation: DisposableOperation, id: String = operation.requestId): String =
        when (operation) {
            DisposableOperation.SessionResume ->
                """{"jsonrpc":"2.0","id":"$id","result":{"session_id":"runtime-7","resumed":"stored-42","messages":[]}}"""
        }

    private enum class DisposableOperation(
        val method: String,
        val requestId: String,
        val transportFailure: String,
        val invalidResponse: String,
    ) {
        SessionResume(
            method = "session.resume",
            requestId = "session-resume",
            transportFailure = "Could not open the Hermes conversation.",
            invalidResponse = "Hermes returned no runtime session identity.",
        ),
        ;

        suspend fun execute(client: DashboardClient, baseUrl: String): Any = when (this) {
            SessionResume -> client.resumeSession(baseUrl, GatewayCredential.None, "stored-42")
        }
    }

    private companion object {
        const val gatewayReadyFrame =
            """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{}}}"""
    }
}
