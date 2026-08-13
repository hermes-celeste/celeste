package dev.hazydreams.hermesceleste.network

import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
data class AuthProvider(
    val name: String = "",
    @SerialName("display_name") val displayName: String = "",
    @SerialName("supports_password") val supportsPassword: Boolean = false,
) {
    val id: String get() = name
}

data class DashboardProbeResult(
    val baseUrl: String,
    val authRequired: Boolean,
    val providers: List<AuthProvider>,
    val version: String?,
) {
    val supportsPassword: Boolean = providers.any(AuthProvider::supportsPassword)
}

data class StoredSession(
    val id: String,
    val title: String,
    val preview: String,
    val startedAt: Double,
    val messageCount: Int,
    val source: String,
    val profile: String = "default",
)

data class DashboardProfile(
    val name: String,
    val isDefault: Boolean = false,
    val model: String? = null,
    val provider: String? = null,
)

data class ConversationMessage(
    val role: String,
    val text: String,
    val toolName: String? = null,
    val id: String? = null,
    val pending: Boolean = false,
    val interim: Boolean = false,
)

data class ResumedSession(
    val runtimeSessionId: String,
    val storedSessionId: String,
    val messages: List<ConversationMessage>,
    val running: Boolean? = null,
    val status: String? = null,
    val inflightAssistantText: String = "",
    val hasLiveProjection: Boolean = false,
)

sealed interface GatewayCredential {
    /** Loopback dashboard with authentication disabled. */
    data object None : GatewayCredential

    /** Loopback/insecure dashboard credential. Never persisted or logged. */
    data class StaticToken(val value: String) : GatewayCredential

    /** Password/OAuth session represented by the client's private cookie jar. */
    data object CookieSession : GatewayCredential
}

interface DashboardService {
    suspend fun probe(rawBaseUrl: String): DashboardProbeResult

    suspend fun passwordLogin(
        baseUrl: String,
        provider: String,
        username: String,
        password: String,
    )

    suspend fun listSessions(
        baseUrl: String,
        credential: GatewayCredential,
        limit: Int = 200,
    ): List<StoredSession>

    suspend fun listProfiles(
        baseUrl: String,
        credential: GatewayCredential,
    ): List<DashboardProfile>

    fun createGateway(baseUrl: String, credential: GatewayCredential): GatewayConnection
}

private class DashboardCookieJar : CookieJar {
    private val cookies = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (cookie in cookies) {
            this.cookies.removeAll {
                it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path
            }
            if (cookie.expiresAt > System.currentTimeMillis()) this.cookies += cookie
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        cookies.removeAll { it.expiresAt <= System.currentTimeMillis() }
        return cookies.filter { it.matches(url) }
    }
}

class DashboardClient(
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val cookieJar: CookieJar = DashboardCookieJar(),
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .build(),
) : DashboardService {
    override suspend fun probe(rawBaseUrl: String): DashboardProbeResult = withContext(Dispatchers.IO) {
        val baseUrl = DashboardUrlPolicy.normalize(rawBaseUrl)
        val status = executeJson(
            Request.Builder()
                .url("$baseUrl/api/status")
                .header("Accept", "application/json")
                .get()
                .build(),
            "Hermes status",
        ).jsonObject
        val authRequired = status["auth_required"]?.jsonPrimitive?.booleanOrNull ?: false
        val providers = if (authRequired) fetchProviders(baseUrl) else emptyList()
        DashboardProbeResult(
            baseUrl = baseUrl,
            authRequired = authRequired,
            providers = providers,
            version = status["version"]?.jsonPrimitive?.contentOrNull,
        )
    }

    override suspend fun passwordLogin(
        baseUrl: String,
        provider: String,
        username: String,
        password: String,
    ) = withContext(Dispatchers.IO) {
        require(username.isNotBlank()) { "Enter your username." }
        require(password.isNotEmpty()) { "Enter your password." }
        val body = json.encodeToString(
            PasswordLoginBody(
                provider = provider,
                username = username,
                password = password,
            ),
        ).toRequestBody(JSON_MEDIA_TYPE)
        executeJson(
            Request.Builder()
                .url("$baseUrl/auth/password-login")
                .header("Accept", "application/json")
                .post(body)
                .build(),
            "Hermes sign-in",
        )
        Unit
    }

    override suspend fun listSessions(
        baseUrl: String,
        credential: GatewayCredential,
        limit: Int,
    ): List<StoredSession> {
        val authParameter = resolveWebSocketCredential(baseUrl, credential)
        val wsUrl = buildWebSocketUrl(baseUrl, authParameter?.first, authParameter?.second)
        return withTimeout(15_000) { requestSessionList(wsUrl, limit.coerceIn(1, 500)) }
    }

    override suspend fun listProfiles(
        baseUrl: String,
        credential: GatewayCredential,
    ): List<DashboardProfile> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/api/profiles")
            .header("Accept", "application/json")
            .apply {
                if (credential is GatewayCredential.StaticToken) {
                    header("X-Hermes-Session-Token", credential.value.trim())
                }
            }
            .get()
            .build()
        val root = executeJson(request, "Hermes profiles") as? JsonObject
            ?: throw IOException("Hermes returned no profile catalog.")
        val profiles = (root["profiles"] as? JsonArray).orEmpty().mapNotNull { element ->
            when (element) {
                is JsonObject -> element["name"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf(String::isNotBlank)
                    ?.let { name ->
                        DashboardProfile(
                            name = name,
                            isDefault = name == "default" ||
                                element["is_default"]?.jsonPrimitive?.booleanOrNull == true,
                            model = element["model"]?.jsonPrimitive?.contentOrNull,
                            provider = element["provider"]?.jsonPrimitive?.contentOrNull,
                        )
                    }

                else -> (element as? JsonPrimitive)?.contentOrNull
                    ?.takeIf(String::isNotBlank)
                    ?.let { DashboardProfile(name = it, isDefault = it == "default") }
            }
        }.distinctBy(DashboardProfile::name)
        if (profiles.any(DashboardProfile::isDefault)) profiles
        else listOf(DashboardProfile(name = "default", isDefault = true)) + profiles
    }

    override fun createGateway(baseUrl: String, credential: GatewayCredential): GatewayConnection =
        HermesGateway(
            httpClient = httpClient,
            json = json,
            endpointProvider = {
                val authParameter = resolveWebSocketCredential(baseUrl, credential)
                buildWebSocketUrl(baseUrl, authParameter?.first, authParameter?.second)
            },
        )

    suspend fun resumeSession(
        baseUrl: String,
        credential: GatewayCredential,
        storedSessionId: String,
    ): ResumedSession {
        require(storedSessionId.isNotBlank()) { "Choose a Hermes session to open." }
        val authParameter = resolveWebSocketCredential(baseUrl, credential)
        val wsUrl = buildWebSocketUrl(baseUrl, authParameter?.first, authParameter?.second)
        return withTimeout(20_000) { requestSessionResume(wsUrl, storedSessionId) }
    }

    private suspend fun resolveWebSocketCredential(
        baseUrl: String,
        credential: GatewayCredential,
    ): Pair<String, String>? = when (credential) {
        GatewayCredential.None -> null
        is GatewayCredential.StaticToken -> "token" to credential.value.trim().also {
            require(it.isNotEmpty()) { "Enter the dashboard session token." }
        }
        GatewayCredential.CookieSession -> "ticket" to withContext(Dispatchers.IO) {
            mintWebSocketTicket(baseUrl)
        }
    }

    private fun fetchProviders(baseUrl: String): List<AuthProvider> {
        val root = executeJson(
            Request.Builder()
                .url("$baseUrl/api/auth/providers")
                .header("Accept", "application/json")
                .get()
                .build(),
            "Hermes authentication providers",
        )
        val providerElement = when (root) {
            is JsonArray -> root
            is JsonObject -> root["providers"] ?: root["_array"]
            else -> null
        } ?: throw IOException("The server response did not include Hermes authentication providers.")
        return runCatching { json.decodeFromJsonElement<List<AuthProvider>>(providerElement) }
            .getOrElse { throw IOException("Hermes authentication providers could not be read.") }
    }

    private fun mintWebSocketTicket(baseUrl: String): String {
        val root = executeJson(
            Request.Builder()
                .url("$baseUrl/api/auth/ws-ticket")
                .header("Accept", "application/json")
                .post(ByteArray(0).toRequestBody(null))
                .build(),
            "Hermes WebSocket ticket",
        ).jsonObject
        return root["ticket"]?.jsonPrimitive?.contentOrNull
            ?.takeIf(String::isNotBlank)
            ?: throw IOException("Hermes did not return a WebSocket ticket.")
    }

    private fun executeJson(request: Request, operation: String) =
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val label = when (response.code) {
                    401, 403 -> "$operation needs sign-in."
                    429 -> "$operation was rate-limited. Try again shortly."
                    else -> "$operation returned HTTP ${response.code}."
                }
                throw IOException(label)
            }
            val body = response.body.string()
            runCatching { json.parseToJsonElement(body) }
                .getOrElse { throw IOException("$operation returned an unreadable response.") }
        }

    private suspend fun requestSessionList(wsUrl: String, limit: Int): List<StoredSession> =
        suspendCancellableCoroutine { continuation ->
            val completed = AtomicBoolean(false)
            lateinit var socket: WebSocket

            fun succeed(sessions: List<StoredSession>) {
                if (completed.compareAndSet(false, true)) {
                    socket.close(1000, "session list received")
                    continuation.resume(sessions)
                }
            }

            fun fail(error: Throwable) {
                if (completed.compareAndSet(false, true)) {
                    socket.cancel()
                    continuation.resumeWithException(error)
                }
            }

            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val request = buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", SESSION_LIST_REQUEST_ID)
                        put("method", "session.list")
                        put("params", buildJsonObject { put("limit", limit) })
                    }
                    if (!webSocket.send(request.toString())) {
                        fail(IOException("Hermes rejected the session-list request."))
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                    if (root["id"]?.jsonPrimitive?.contentOrNull != SESSION_LIST_REQUEST_ID) return
                    root["error"]?.jsonObject?.let { error ->
                        val message = error["message"]?.jsonPrimitive?.contentOrNull ?: "Hermes session list failed."
                        fail(IOException(message))
                        return
                    }
                    val rows = root["result"]?.jsonObject?.get("sessions")?.jsonArray
                        ?: run {
                            fail(IOException("Hermes returned no session list."))
                            return
                        }
                    succeed(rows.mapNotNull(::decodeSession))
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val message = when (response?.code) {
                        401, 403 -> "Hermes rejected the dashboard credential."
                        else -> "Could not open the Hermes session connection."
                    }
                    fail(IOException(message, t))
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!completed.get()) fail(IOException("Hermes closed the connection before returning sessions."))
                }
            }

            socket = httpClient.newWebSocket(Request.Builder().url(wsUrl).build(), listener)
            continuation.invokeOnCancellation {
                if (completed.compareAndSet(false, true)) socket.cancel()
            }
        }

    private suspend fun requestSessionResume(
        wsUrl: String,
        storedSessionId: String,
    ): ResumedSession = suspendCancellableCoroutine { continuation ->
        val completed = AtomicBoolean(false)
        lateinit var socket: WebSocket

        fun succeed(session: ResumedSession) {
            if (completed.compareAndSet(false, true)) {
                socket.close(1000, "session resumed")
                continuation.resume(session)
            }
        }

        fun fail(error: Throwable) {
            if (completed.compareAndSet(false, true)) {
                socket.cancel()
                continuation.resumeWithException(error)
            }
        }

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val request = buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", SESSION_RESUME_REQUEST_ID)
                    put("method", "session.resume")
                    put(
                        "params",
                        buildJsonObject {
                            put("session_id", storedSessionId)
                            put("cols", 80)
                            put("source", "android")
                        },
                    )
                }
                if (!webSocket.send(request.toString())) {
                    fail(IOException("Hermes rejected the session-resume request."))
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                if (root["id"]?.jsonPrimitive?.contentOrNull != SESSION_RESUME_REQUEST_ID) return
                root["error"]?.jsonObject?.let { error ->
                    val message = error["message"]?.jsonPrimitive?.contentOrNull
                        ?: "Hermes could not open that session."
                    fail(IOException(message))
                    return
                }
                val result = root["result"]?.jsonObject
                    ?: run {
                        fail(IOException("Hermes returned no resumed session."))
                        return
                    }
                val runtimeId = result["session_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (runtimeId.isBlank()) {
                    fail(IOException("Hermes returned no runtime session identity."))
                    return
                }
                val resumedId = result["resumed"]?.jsonPrimitive?.contentOrNull
                    ?: result["session_key"]?.jsonPrimitive?.contentOrNull
                    ?: storedSessionId
                val messages = result["messages"]?.jsonArray.orEmpty().mapNotNull(::decodeMessage)
                succeed(
                    ResumedSession(
                        runtimeSessionId = runtimeId,
                        storedSessionId = resumedId,
                        messages = messages,
                    ),
                )
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val message = when (response?.code) {
                    401, 403 -> "Hermes rejected the dashboard credential."
                    else -> "Could not open the Hermes conversation."
                }
                fail(IOException(message, t))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!completed.get()) {
                    fail(IOException("Hermes closed the connection before opening the conversation."))
                }
            }
        }

        socket = httpClient.newWebSocket(Request.Builder().url(wsUrl).build(), listener)
        continuation.invokeOnCancellation {
            if (completed.compareAndSet(false, true)) socket.cancel()
        }
    }

    private fun decodeSession(element: kotlinx.serialization.json.JsonElement): StoredSession? {
        val row = element as? JsonObject ?: return null
        val id = row["id"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: return null
        return StoredSession(
            id = id,
            title = row["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            preview = row["preview"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            startedAt = row["started_at"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            messageCount = row["message_count"]?.jsonPrimitive?.intOrNull ?: 0,
            source = row["source"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            profile = row["profile"]?.jsonPrimitive?.contentOrNull
                ?: row["profile_name"]?.jsonPrimitive?.contentOrNull
                ?: "default",
        )
    }

    private fun decodeMessage(element: kotlinx.serialization.json.JsonElement): ConversationMessage? {
        val row = element as? JsonObject ?: return null
        val role = row["role"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: return null
        val toolName = row["name"]?.jsonPrimitive?.contentOrNull
        val text = row["text"]?.jsonPrimitive?.contentOrNull
            ?: row["context"]?.jsonPrimitive?.contentOrNull
            ?: if (role == "tool") toolName.orEmpty() else ""
        return ConversationMessage(role = role, text = text, toolName = toolName)
    }

    private fun buildWebSocketUrl(baseUrl: String, parameter: String?, value: String?): String {
        val builder = "$baseUrl/api/ws".toHttpUrl().newBuilder()
        if (parameter != null && value != null) builder.addQueryParameter(parameter, value)
        val httpUrl = builder.build()
            .toString()
        return when {
            httpUrl.startsWith("https://") -> "wss://${httpUrl.removePrefix("https://")}"
            else -> "ws://${httpUrl.removePrefix("http://")}"
        }
    }

    @Serializable
    private data class PasswordLoginBody(
        val provider: String,
        val username: String,
        val password: String,
        val next: String = "",
    )

    private companion object {
        const val SESSION_LIST_REQUEST_ID = "session-list"
        const val SESSION_RESUME_REQUEST_ID = "session-resume"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
