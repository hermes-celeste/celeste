package dev.hazydreams.hermesceleste.network

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

sealed interface GatewayConnectionState {
    data object Idle : GatewayConnectionState
    data object Connecting : GatewayConnectionState
    data object Connected : GatewayConnectionState
    data class Disconnected(val reason: String) : GatewayConnectionState
    data object Closed : GatewayConnectionState
}

data class GatewayEvent(
    val type: String,
    val sessionId: String,
    val payload: JsonObject,
)

class GatewayRpcException(
    val code: Int?,
    message: String,
) : IOException(message)

interface GatewayConnection {
    val state: StateFlow<GatewayConnectionState>
    val events: SharedFlow<GatewayEvent>

    /** True only when gateway.ready advertised the optional invalidation event. */
    val supportsSessionChangeEvents: Boolean
        get() = false

    suspend fun connect()

    suspend fun request(
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
        timeoutMillis: Long = DEFAULT_RPC_TIMEOUT_MILLIS,
    ): JsonElement

    fun close()

    companion object {
        const val DEFAULT_RPC_TIMEOUT_MILLIS = 30_000L
    }
}

/**
 * Thin JSON-RPC transport. Session ownership, turn state, reconciliation, and
 * reconnect policy intentionally live above this layer.
 */
class HermesGateway(
    private val httpClient: OkHttpClient,
    private val json: Json,
    private val endpointProvider: suspend () -> String,
    private val connectTimeoutMillis: Long = 15_000L,
) : GatewayConnection {
    private val connectMutex = Mutex()
    private val requestCounter = AtomicLong(0)
    private val socketGeneration = AtomicLong(0)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonElement>>()
    private val mutableState = MutableStateFlow<GatewayConnectionState>(GatewayConnectionState.Idle)
    private val mutableEvents = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 128)

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var intentionalClose = false

    @Volatile
    private var sessionChangeEventsSupported = false

    override val state: StateFlow<GatewayConnectionState> = mutableState
    override val events: SharedFlow<GatewayEvent> = mutableEvents
    override val supportsSessionChangeEvents: Boolean
        get() = sessionChangeEventsSupported

    override suspend fun connect(): Unit = connectMutex.withLock {
        if (mutableState.value == GatewayConnectionState.Connected && socket != null) return

        intentionalClose = false
        sessionChangeEventsSupported = false
        socket?.cancel()
        socket = null
        failPending(IOException("Hermes connection was replaced."))
        mutableState.value = GatewayConnectionState.Connecting

        val endpoint = try {
            endpointProvider()
        } catch (error: Throwable) {
            mutableState.value = GatewayConnectionState.Disconnected(
                error.message ?: "Could not refresh the Hermes connection.",
            )
            throw error
        }

        val generation = socketGeneration.incrementAndGet()
        val opened = CompletableDeferred<Unit>()
        lateinit var candidate: WebSocket
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (generation != socketGeneration.get()) {
                    webSocket.cancel()
                    return
                }
                socket = webSocket
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (generation != socketGeneration.get()) return
                val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                val eventType = root["params"]
                    ?.let { it as? JsonObject }
                    ?.get("type")
                    ?.let { it as? JsonPrimitive }
                    ?.contentOrNull
                if (root["method"]?.jsonPrimitive?.contentOrNull == "event" && eventType == "gateway.ready") {
                    socket = webSocket
                    sessionChangeEventsSupported = root["params"]
                        ?.let { it as? JsonObject }
                        ?.get("payload")
                        ?.let { it as? JsonObject }
                        ?.get("change_events")
                        ?.let { it as? JsonPrimitive }
                        ?.booleanOrNull == true
                    mutableState.value = GatewayConnectionState.Connected
                    opened.complete(Unit)
                    return
                }
                handleFrame(root)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (generation != socketGeneration.get()) return
                handleDisconnect(
                    reason = reason.ifBlank { "Hermes closed the connection ($code)." },
                    opened = opened,
                )
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (generation != socketGeneration.get()) return
                val reason = when (response?.code) {
                    401, 403 -> "Hermes rejected the dashboard credential."
                    else -> t.message ?: "The Hermes connection failed."
                }
                val error = when (response?.code) {
                    401, 403 -> AuthenticationRejected(reason)
                    else -> IOException(reason, t)
                }
                handleDisconnect(reason, opened, error)
            }
        }

        candidate = httpClient.newWebSocket(Request.Builder().url(endpoint).build(), listener)
        socket = candidate
        try {
            withTimeout(connectTimeoutMillis) { opened.await() }
        } catch (error: Throwable) {
            if (socket === candidate) {
                candidate.cancel()
                socket = null
                if (mutableState.value !is GatewayConnectionState.Disconnected) {
                    mutableState.value = GatewayConnectionState.Disconnected(
                        error.message ?: "Hermes did not finish connecting.",
                    )
                }
            }
            throw error
        }
    }

    override suspend fun request(
        method: String,
        params: JsonObject,
        timeoutMillis: Long,
    ): JsonElement {
        require(method.isNotBlank()) { "A Hermes RPC method is required." }
        val activeSocket = socket
        if (mutableState.value != GatewayConnectionState.Connected || activeSocket == null) {
            throw IOException("Hermes is not connected.")
        }

        val id = "android-${requestCounter.incrementAndGet()}"
        val deferred = CompletableDeferred<JsonElement>()
        pending[id] = deferred
        val frame = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        if (!activeSocket.send(frame.toString())) {
            pending.remove(id)
            throw IOException("Hermes rejected the $method request.")
        }

        return try {
            withTimeout(timeoutMillis) { deferred.await() }
        } finally {
            pending.remove(id)
        }
    }

    override fun close() {
        intentionalClose = true
        sessionChangeEventsSupported = false
        socketGeneration.incrementAndGet()
        val active = socket
        socket = null
        mutableState.value = GatewayConnectionState.Closed
        failPending(IOException("Hermes connection closed."))
        active?.close(1000, "Celeste closed the connection")
    }

    private fun handleFrame(root: JsonObject) {
        val id = root["id"]?.jsonPrimitive?.contentOrNull
        if (id != null) {
            val deferred = pending.remove(id) ?: return
            val error = root["error"] as? JsonObject
            if (error != null) {
                deferred.completeExceptionally(
                    GatewayRpcException(
                        code = error["code"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
                        message = error["message"]?.jsonPrimitive?.contentOrNull
                            ?: "Hermes request failed.",
                    ),
                )
            } else {
                deferred.complete(root["result"] ?: JsonNull)
            }
            return
        }

        if (root["method"]?.jsonPrimitive?.contentOrNull != "event") return
        val params = root["params"] as? JsonObject ?: return
        val type = params["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (type.isBlank()) return
        mutableEvents.tryEmit(
            GatewayEvent(
                type = type,
                sessionId = params["session_id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                payload = params["payload"] as? JsonObject ?: JsonObject(emptyMap()),
            ),
        )
    }

    private fun handleDisconnect(
        reason: String,
        opened: CompletableDeferred<Unit>,
        error: Throwable = IOException(reason),
    ) {
        socket = null
        sessionChangeEventsSupported = false
        if (!intentionalClose) mutableState.value = GatewayConnectionState.Disconnected(reason)
        if (!opened.isCompleted) opened.completeExceptionally(error)
        failPending(error)
    }

    private fun failPending(error: Throwable) {
        val requests = pending.values.toList()
        pending.clear()
        requests.forEach { it.completeExceptionally(error) }
    }
}
