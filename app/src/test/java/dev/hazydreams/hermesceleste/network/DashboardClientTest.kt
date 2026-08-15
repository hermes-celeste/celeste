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
import org.junit.Assert.assertNull
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
    fun listsAuthoritativeRestPageWithActivityAndMetadata() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """{"sessions":[{"id":"new","title":"New","preview":"","started_at":100,"last_active":200,"message_count":2,"source":"desktop"},{"id":"old","title":"Old","preview":"","started_at":90,"last_active":190,"message_count":1,"source":"cli","profile":"work"}],"total":7,"offset":0,"limit":200,"errors":[{"profile":"locked","error":"unavailable"}]}""",
                )
                .build(),
        )

        val baseUrl = server.url("/").toString().trimEnd('/')
        val page = DashboardClient().listSessionPage(
            baseUrl = baseUrl,
            credential = GatewayCredential.StaticToken("private-token"),
            profile = "work",
            limit = 200,
            offset = 0,
        )

        assertEquals(SessionOrdering.AUTHORITATIVE_RECENCY, page.ordering)
        assertEquals(7, page.total)
        assertEquals(200, page.limit)
        assertEquals(200.0, page.sessions.first().lastActive!!, 0.0)
        assertEquals("work", page.sessions.first().profile)
        assertEquals(listOf("locked"), page.errors.map(SessionListError::profile))
        val request = server.takeRequest()
        assertEquals("/api/profiles/sessions", request.url.encodedPath)
        assertEquals("work", request.url.queryParameter("profile"))
        assertEquals("recent", request.url.queryParameter("order"))
        assertEquals("exclude", request.url.queryParameter("archived"))
        assertEquals("200", request.url.queryParameter("limit"))
        assertEquals("0", request.url.queryParameter("offset"))
        assertEquals("private-token", request.headers["X-Hermes-Session-Token"])
    }

    @Test
    fun rejectsInvalidActivityNumbersWithoutUsingTheDeviceClock() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """{"sessions":[{"id":"zero","title":"Zero","started_at":40,"last_active":0},{"id":"negative","title":"Negative","started_at":30,"last_active":-1},{"id":"nan","title":"NaN","started_at":0,"last_active":"NaN"}]}""",
                )
                .build(),
        )

        val page = DashboardClient().listSessionPage(
            baseUrl = server.url("/").toString().trimEnd('/'),
            credential = GatewayCredential.None,
        )

        assertNull(page.sessions[0].lastActive)
        assertEquals(40.0, page.sessions[0].startedAt, 0.0)
        assertNull(page.sessions[1].lastActive)
        assertEquals(30.0, page.sessions[1].startedAt, 0.0)
        assertNull(page.sessions[2].lastActive)
        assertEquals(0.0, page.sessions[2].startedAt, 0.0)
        assertEquals(SessionOrdering.SERVER_ORDER, page.ordering)
    }

    @Test
    fun malformedEpochShapesBecomeUnknownInsteadOfCrashingTheListDecoder() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"sessions":[{"id":"object-value","started_at":{},"last_active":[]}]}""")
                .build(),
        )

        val page = DashboardClient().listSessionPage(
            baseUrl = server.url("/").toString().trimEnd('/'),
            credential = GatewayCredential.None,
        )

        assertEquals(0.0, page.sessions.single().startedAt, 0.0)
        assertNull(page.sessions.single().lastActive)
        assertEquals(SessionOrdering.SERVER_ORDER, page.ordering)
    }

    @Test
    fun missingRestActivityPermanentlyFallsBackToRpcForThisGeneration() = runBlocking {
        val client = DashboardClient()
        val baseUrl = server.url("/").toString().trimEnd('/')
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"sessions":[{"id":"legacy","started_at":40}]}""")
                .build(),
        )
        server.enqueue(sessionListWebSocket())
        server.enqueue(sessionListWebSocket())

        val first = client.listSessionPage(baseUrl, GatewayCredential.None)
        val second = client.listSessionPage(baseUrl, GatewayCredential.None)

        assertEquals(SessionOrdering.SERVER_ORDER, first.ordering)
        assertEquals(listOf("s1", "s2"), second.sessions.map(StoredSession::id))
        assertEquals(3, server.requestCount)
        assertEquals("/api/profiles/sessions", server.takeRequest().url.encodedPath)
        assertEquals("/api/ws", server.takeRequest().url.encodedPath)
        assertEquals("/api/ws", server.takeRequest().url.encodedPath)
    }

    @Test
    fun unsupportedRpcMethodIsInvalidatedUntilTheAuthenticationGenerationChanges() = runBlocking {
        val client = DashboardClient()
        val baseUrl = server.url("/").toString().trimEnd('/')
        server.enqueue(MockResponse.Builder().code(404).build())
        server.enqueue(rpcErrorWebSocket(code = -32601))

        assertThrows(InvalidDashboardResponse::class.java) {
            runBlocking { client.listSessionPage(baseUrl, GatewayCredential.None) }
        }
        assertThrows(InvalidDashboardResponse::class.java) {
            runBlocking { client.listSessionPage(baseUrl, GatewayCredential.None) }
        }
        assertEquals(2, server.requestCount)

        client.clearAuthentication()
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"sessions":[]}""")
                .build(),
        )
        assertTrue(client.listSessionPage(baseUrl, GatewayCredential.None).sessions.isEmpty())
        assertEquals(3, server.requestCount)
    }

    @Test
    fun fallsBackToServerOrderedRpcWhenTheRestListIsUnavailable() = runBlocking {
        server.enqueue(MockResponse.Builder().code(404).build())
        server.enqueue(sessionListWebSocket())

        val page = DashboardClient().listSessionPage(
            baseUrl = server.url("/").toString().trimEnd('/'),
            credential = GatewayCredential.None,
        )

        assertEquals(SessionOrdering.SERVER_ORDER, page.ordering)
        assertEquals(listOf("s1", "s2"), page.sessions.map(StoredSession::id))
        assertEquals("/api/profiles/sessions", server.takeRequest().url.encodedPath)
        assertEquals("/api/ws", server.takeRequest().url.encodedPath)
    }

    @Test
    fun boundsRestPaginationAndPreservesTheServerPageMetadata() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"sessions":[],"total":201,"offset":0,"limit":200}""")
                .build(),
        )

        val page = DashboardClient().listSessionPage(
            baseUrl = server.url("/").toString().trimEnd('/'),
            credential = GatewayCredential.None,
            profile = "all",
            limit = 999,
            offset = -5,
        )

        assertEquals(201, page.total)
        assertEquals(0, page.offset)
        assertEquals(200, page.limit)
        val request = server.takeRequest()
        assertEquals("200", request.url.queryParameter("limit"))
        assertEquals("0", request.url.queryParameter("offset"))
    }

    @Test
    fun legacyRpcCarriesProfileAndPreservesExactNumericActivityWhenAvailable() = runBlocking {
        val requestReceived = CompletableDeferred<JsonObject>()
        server.enqueue(MockResponse.Builder().code(404).build())
        server.enqueue(
            MockResponse.Builder()
                .webSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            requestReceived.complete(Json.parseToJsonElement(text).jsonObject)
                            webSocket.send(
                                """{"jsonrpc":"2.0","id":"session-list","result":{"sessions":[{"id":"work-row","profile":"work","started_at":100,"last_active":200}]}}""",
                            )
                        }

                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            webSocket.close(code, reason)
                        }
                    },
                )
                .build(),
        )

        val page = DashboardClient().listSessionPage(
            baseUrl = server.url("/").toString().trimEnd('/'),
            credential = GatewayCredential.None,
            profile = "work",
            limit = 20,
            offset = 40,
        )

        val request = withTimeout(5_000) { requestReceived.await() }
        assertEquals("work", request["params"]?.jsonObject?.get("profile")?.jsonPrimitive?.content)
        assertEquals("20", request["params"]?.jsonObject?.get("limit")?.jsonPrimitive?.content)
        assertEquals(SessionOrdering.AUTHORITATIVE_RECENCY, page.ordering)
        assertEquals(200.0, page.sessions.single().lastActive!!, 0.0)
    }

    @Test
    fun restCapabilityIsInvalidatedWhenAuthenticationIsCleared() = runBlocking {
        val client = DashboardClient()
        val baseUrl = server.url("/").toString().trimEnd('/')
        server.enqueue(MockResponse.Builder().code(404).build())
        server.enqueue(sessionListWebSocket())
        client.listSessionPage(baseUrl, GatewayCredential.None)

        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"sessions":[{"id":"rest-row","started_at":10,"last_active":20}]}""")
                .build(),
        )
        client.clearAuthentication()
        val page = client.listSessionPage(baseUrl, GatewayCredential.None)

        assertEquals(listOf("rest-row"), page.sessions.map(StoredSession::id))
        assertEquals("/api/profiles/sessions", server.takeRequest().url.encodedPath)
        assertEquals("/api/ws", server.takeRequest().url.encodedPath)
        assertEquals("/api/profiles/sessions", server.takeRequest().url.encodedPath)
    }

    @Test
    fun restAuthenticationFailureDoesNotFallBackToLegacyRpc() = runBlocking {
        server.enqueue(MockResponse.Builder().code(401).build())

        val failure = runCatching {
            DashboardClient().listSessionPage(
                baseUrl = server.url("/").toString().trimEnd('/'),
                credential = GatewayCredential.None,
            )
        }.exceptionOrNull()

        assertTrue(failure is AuthenticationRejected)
        assertEquals(1, server.requestCount)
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
        assertEquals(listOf("row-41", "resume-1", "resume-2", "resume-3", "final"), disposable.messages.map { it.id })
        assertEquals(listOf(null, null, "terminal", "terminal", null), disposable.messages.map { it.toolName })
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
    fun missingLegacyProfileCatalogUsesOnlyTheDefaultProfile() = runTest {
        server.enqueue(MockResponse.Builder().code(404).body("not found").build())
        val baseUrl = server.url("/").toString().trimEnd('/')

        val profiles = DashboardClient().listProfiles(baseUrl, GatewayCredential.None)

        assertEquals(listOf(DashboardProfile(name = "default", isDefault = true)), profiles)
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

            when (operation) {
                DisposableOperation.SessionList -> assertTrue((result as List<*>).isEmpty())
                DisposableOperation.SessionResume -> assertEquals("runtime-7", (result as ResumedSession).runtimeSessionId)
            }
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

    private fun rpcErrorWebSocket(code: Int): MockResponse =
        MockResponse.Builder()
            .webSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        webSocket.send(
                            """{"jsonrpc":"2.0","id":"session-list","error":{"code":$code,"message":"method unavailable"}}""",
                        )
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, reason)
                    }
                },
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
            DisposableOperation.SessionList ->
                """{"jsonrpc":"2.0","id":"$id","result":{"sessions":[]}}"""
            DisposableOperation.SessionResume ->
                """{"jsonrpc":"2.0","id":"$id","result":{"session_id":"runtime-7","resumed":"stored-42","messages":[]}}"""
        }

    private enum class DisposableOperation(
        val method: String,
        val requestId: String,
        val transportFailure: String,
        val invalidResponse: String,
    ) {
        SessionList(
            method = "session.list",
            requestId = "session-list",
            transportFailure = "Could not open the Hermes session connection.",
            invalidResponse = "Hermes returned no session list.",
        ),
        SessionResume(
            method = "session.resume",
            requestId = "session-resume",
            transportFailure = "Could not open the Hermes conversation.",
            invalidResponse = "Hermes returned no runtime session identity.",
        ),
        ;

        suspend fun execute(client: DashboardClient, baseUrl: String): Any = when (this) {
            SessionList -> client.listSessions(baseUrl, GatewayCredential.None)
            SessionResume -> client.resumeSession(baseUrl, GatewayCredential.None, "stored-42")
        }
    }

    private companion object {
        const val gatewayReadyFrame =
            """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{}}}"""
    }
}
