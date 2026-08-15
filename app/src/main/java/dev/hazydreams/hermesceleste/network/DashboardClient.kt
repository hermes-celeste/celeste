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
import kotlinx.serialization.json.JsonElement
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
    class StaticToken(val value: String) : GatewayCredential {
        override fun toString(): String = "StaticToken([REDACTED])"
    }

    /** Password/OAuth session represented by the client's private cookie jar. */
    data object CookieSession : GatewayCredential
}

@JvmInline
value class AuthenticationMaterial(val value: String) {
    override fun toString(): String = "[REDACTED]"
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

    suspend fun logout(baseUrl: String) = Unit

    fun exportAuthentication(baseUrl: String): AuthenticationMaterial? = null

    fun restoreAuthentication(baseUrl: String, material: AuthenticationMaterial): Boolean = false

    fun clearAuthentication() = Unit

    fun createGateway(baseUrl: String, credential: GatewayCredential): GatewayConnection
}

sealed class DashboardFailure(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

class AuthenticationRejected(message: String) : DashboardFailure(message)

class RateLimited(message: String) : DashboardFailure(message)

class TransportUnavailable(
    message: String,
    cause: Throwable? = null,
    internal val statusCode: Int? = null,
) : DashboardFailure(message, cause)

class InvalidDashboardResponse(message: String, cause: Throwable? = null) : DashboardFailure(message, cause)

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

    @Synchronized
    fun clear() {
        cookies.clear()
    }

    @Synchronized
    fun authenticatedCookies(url: HttpUrl): List<Cookie> {
        cookies.removeAll { it.expiresAt <= System.currentTimeMillis() }
        return cookies.filter { cookie ->
            cookie.matches(url) && cookie.name in HERMES_SESSION_COOKIE_NAMES
        }
    }

    @Synchronized
    fun replaceAuthenticatedCookies(url: HttpUrl, restored: List<Cookie>): Boolean {
        val now = System.currentTimeMillis()
        val usable = restored.filter { it.expiresAt > now }
        if (usable.none { it.name in HERMES_CREDENTIAL_COOKIE_NAMES } || usable.any { cookie ->
                cookie.name !in HERMES_SESSION_COOKIE_NAMES ||
                    cookie.domain != url.host ||
                    !cookie.matches(url)
            }
        ) {
            return false
        }
        cookies.clear()
        cookies += usable
        return true
    }

    private companion object {
        val HERMES_CREDENTIAL_COOKIE_NAMES = setOf(
            "hermes_session_at",
            "hermes_session_rt",
            "__Secure-hermes_session_at",
            "__Secure-hermes_session_rt",
            "__Host-hermes_session_at",
            "__Host-hermes_session_rt",
        )
        val HERMES_SESSION_COOKIE_NAMES = setOf(
            *HERMES_CREDENTIAL_COOKIE_NAMES.toTypedArray(),
            "hermes_session_provider",
            "__Secure-hermes_session_provider",
            "__Host-hermes_session_provider",
        )
    }
}

@Serializable
private data class PersistedDashboardCookie(
    val name: String,
    val value: String,
    val expiresAt: Long,
    val domain: String,
    val path: String,
    val secure: Boolean,
    val httpOnly: Boolean,
    val hostOnly: Boolean,
) {
    override fun toString(): String =
        "PersistedDashboardCookie(name=$name, value=[REDACTED], domain=$domain, path=$path)"

    fun toCookie(): Cookie = Cookie.Builder()
        .name(name)
        .value(value)
        .expiresAt(expiresAt)
        .apply {
            if (hostOnly) hostOnlyDomain(domain) else domain(domain)
            path(path)
            if (secure) secure()
            if (httpOnly) httpOnly()
        }
        .build()

    companion object {
        fun from(cookie: Cookie) = PersistedDashboardCookie(
            name = cookie.name,
            value = cookie.value,
            expiresAt = cookie.expiresAt,
            domain = cookie.domain,
            path = cookie.path,
            secure = cookie.secure,
            httpOnly = cookie.httpOnly,
            hostOnly = cookie.hostOnly,
        )
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
        ) as? JsonObject ?: throw InvalidDashboardResponse(
            "Hermes status returned an unexpected response.",
        )
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
        clearAuthentication()
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
        val root = try {
            executeJson(request, "Hermes profiles")
        } catch (error: TransportUnavailable) {
            if (error.statusCode == 404) {
                return@withContext listOf(DashboardProfile(name = "default", isDefault = true))
            }
            throw error
        } as? JsonObject ?: throw InvalidDashboardResponse("Hermes returned no profile catalog.")
        val rows = root["profiles"] as? JsonArray
            ?: throw InvalidDashboardResponse("Hermes returned no profile catalog.")
        val profiles = rows.map(::decodeProfile).distinctBy(DashboardProfile::name)
        if (profiles.any(DashboardProfile::isDefault)) profiles
        else listOf(DashboardProfile(name = "default", isDefault = true)) + profiles
    }

    override suspend fun logout(baseUrl: String) = withContext(Dispatchers.IO) {
        val normalizedBaseUrl = DashboardUrlPolicy.normalize(baseUrl)
        val request = Request.Builder()
            .url("$normalizedBaseUrl/auth/logout")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        try {
            httpClient.newCall(request).execute().use { response ->
                if (response.code !in 200..399) throw failureFor(response.code, "Hermes sign-out")
            }
        } catch (error: DashboardFailure) {
            throw error
        } catch (error: IOException) {
            throw TransportUnavailable("Could not reach Hermes to sign out.", error)
        }
    }

    override fun exportAuthentication(baseUrl: String): AuthenticationMaterial? {
        val jar = cookieJar as? DashboardCookieJar ?: return null
        val url = authenticationScopeUrl(baseUrl) ?: return null
        val cookies = jar.authenticatedCookies(url)
        if (cookies.isEmpty()) return null
        return AuthenticationMaterial(json.encodeToString(cookies.map(PersistedDashboardCookie::from)))
    }

    override fun restoreAuthentication(baseUrl: String, material: AuthenticationMaterial): Boolean {
        val jar = cookieJar as? DashboardCookieJar ?: return false
        jar.clear()
        val url = authenticationScopeUrl(baseUrl) ?: return false
        val cookies = runCatching {
            json.decodeFromString<List<PersistedDashboardCookie>>(material.value).map { it.toCookie() }
        }.getOrNull() ?: return false
        return jar.replaceAuthenticatedCookies(url, cookies)
    }

    override fun clearAuthentication() {
        (cookieJar as? DashboardCookieJar)?.clear()
    }

    private fun authenticationScopeUrl(baseUrl: String): HttpUrl? = runCatching {
        "${DashboardUrlPolicy.normalize(baseUrl)}/api/status".toHttpUrl()
    }.getOrNull()

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

    private fun executeJson(request: Request, operation: String) = try {
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw failureFor(response.code, operation)
            val body = response.body.string()
            runCatching { json.parseToJsonElement(body) }
                .getOrElse { throw InvalidDashboardResponse("$operation returned an unreadable response.", it) }
        }
    } catch (error: DashboardFailure) {
        throw error
    } catch (error: IOException) {
        throw TransportUnavailable("Could not reach Hermes for $operation.", error)
    }

    private fun failureFor(code: Int, operation: String): DashboardFailure = when (code) {
        401, 403 -> AuthenticationRejected("$operation needs sign-in.")
        429 -> RateLimited("$operation was rate-limited. Try again shortly.")
        else -> TransportUnavailable("$operation returned HTTP $code.", statusCode = code)
    }

    private suspend fun requestSessionList(wsUrl: String, limit: Int): List<StoredSession> {
        val frame = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", SESSION_LIST_REQUEST_ID)
            put("method", "session.list")
            put("params", buildJsonObject { put("limit", limit) })
        }
        return requestSingleWebSocketResponse(
            request = Request.Builder().url(wsUrl).build(),
            frame = frame,
            expectedId = SESSION_LIST_REQUEST_ID,
            operation = "Hermes session connection",
        ) { result ->
            val rows = (result as? JsonObject)?.get("sessions") as? JsonArray
                ?: throw InvalidDashboardResponse("Hermes returned no session list.")
            rows.mapNotNull(::decodeSession)
        }
    }

    private suspend fun requestSessionResume(
        wsUrl: String,
        storedSessionId: String,
    ): ResumedSession {
        val frame = buildJsonObject {
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
        return requestSingleWebSocketResponse(
            request = Request.Builder().url(wsUrl).build(),
            frame = frame,
            expectedId = SESSION_RESUME_REQUEST_ID,
            operation = "Hermes conversation",
        ) { element ->
            val result = element as? JsonObject
                ?: throw InvalidDashboardResponse("Hermes returned no resumed session.")
            val runtimeId = result["session_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (runtimeId.isBlank()) {
                throw InvalidDashboardResponse("Hermes returned no runtime session identity.")
            }
            ResumedSession(
                runtimeSessionId = runtimeId,
                storedSessionId = result["resumed"]?.jsonPrimitive?.contentOrNull
                    ?: result["session_key"]?.jsonPrimitive?.contentOrNull
                    ?: storedSessionId,
                messages = decodeGatewayMessages(result["messages"]?.jsonArray.orEmpty()),
            )
        }
    }

    private suspend fun <T> requestSingleWebSocketResponse(
        request: Request,
        frame: JsonObject,
        expectedId: String,
        operation: String,
        decode: (JsonElement) -> T,
    ): T = suspendCancellableCoroutine { continuation ->
        val completed = AtomicBoolean(false)
        lateinit var socket: WebSocket

        fun succeed(value: T) {
            if (completed.compareAndSet(false, true)) {
                socket.close(1000, "response received")
                continuation.resume(value)
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
                if (!webSocket.send(frame.toString())) {
                    fail(TransportUnavailable("$operation rejected the request."))
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val root = runCatching { json.parseToJsonElement(text) as? JsonObject }
                    .getOrNull()
                    ?: run {
                        fail(InvalidDashboardResponse("$operation returned an unreadable response."))
                        return
                    }
                if ((root["id"] as? JsonPrimitive)?.contentOrNull != expectedId) return
                (root["error"] as? JsonObject)?.let { error ->
                    fail(IOException((error["message"] as? JsonPrimitive)?.contentOrNull ?: "$operation failed."))
                    return
                }
                val result = root["result"]
                    ?: run {
                        fail(InvalidDashboardResponse("$operation returned no response."))
                        return
                    }
                runCatching { decode(result) }
                    .onSuccess(::succeed)
                    .onFailure { error ->
                        fail(
                            if (error is DashboardFailure) {
                                error
                            } else {
                                InvalidDashboardResponse("$operation returned an unreadable response.", error)
                            },
                        )
                    }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                fail(
                    when (response?.code) {
                        401, 403 -> AuthenticationRejected("Hermes rejected the dashboard credential.")
                        429 -> RateLimited("Hermes rate-limited the dashboard connection.")
                        else -> TransportUnavailable("Could not open the $operation.", t)
                    },
                )
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                fail(TransportUnavailable("$operation closed before returning a response."))
            }
        }

        socket = httpClient.newWebSocket(request, listener)
        continuation.invokeOnCancellation {
            if (completed.compareAndSet(false, true)) socket.cancel()
        }
    }

    private fun decodeSession(element: JsonElement): StoredSession? {
        val row = element as? JsonObject ?: return null
        val id = row["id"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: return null
        val profile = sequenceOf("profile", "profile_name")
            .mapNotNull { key ->
                (row[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
            }
            .firstOrNull()
        return StoredSession(
            id = id,
            title = row["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            preview = row["preview"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            startedAt = row["started_at"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            messageCount = row["message_count"]?.jsonPrimitive?.intOrNull ?: 0,
            source = row["source"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            profile = profile.orEmpty(),
        )
    }

    private fun decodeProfile(element: JsonElement): DashboardProfile {
        if (element is JsonPrimitive && element.isString) {
            val name = element.contentOrNull?.takeIf(String::isNotBlank)
                ?: throw InvalidDashboardResponse("Hermes returned an invalid profile catalog.")
            return DashboardProfile(name = name, isDefault = name == "default")
        }
        val row = element as? JsonObject
            ?: throw InvalidDashboardResponse("Hermes returned an invalid profile catalog.")
        val name = (row["name"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.contentOrNull
            ?.takeIf(String::isNotBlank)
            ?: throw InvalidDashboardResponse("Hermes returned an invalid profile catalog.")
        return DashboardProfile(
            name = name,
            isDefault = name == "default" ||
                (row["is_default"] as? JsonPrimitive)?.booleanOrNull == true,
            model = (row["model"] as? JsonPrimitive)?.contentOrNull,
            provider = (row["provider"] as? JsonPrimitive)?.contentOrNull,
        )
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
