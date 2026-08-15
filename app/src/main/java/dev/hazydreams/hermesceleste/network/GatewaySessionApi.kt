package dev.hazydreams.hermesceleste.network

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class CreatedSession(
    val runtimeSessionId: String,
    val storedSessionId: String,
    val profile: String?,
    val runtimeControls: RuntimeControlsInfo = RuntimeControlsInfo(),
)

enum class RuntimeControlsSource {
    ResumedSnapshot,
    SessionInfo,
    ApplyAcknowledgement,
    ConfigFallback,
}

enum class RuntimeControlsWriteStatus {
    Accepted,
    Deferred,
    Rejected,
}

data class RuntimeControlsWriteOutcome(
    val key: String,
    val status: RuntimeControlsWriteStatus,
    val authoritativeInfo: RuntimeControlsInfo? = null,
)

data class RuntimeModelOption(
    val provider: String,
    val model: String,
    val supportsReasoning: Boolean = false,
    val supportsFast: Boolean = false,
)

data class RuntimeControlsCapabilities(
    val available: Boolean,
    val modelOptions: List<RuntimeModelOption> = emptyList(),
    val reasoningEfforts: List<String> = emptyList(),
    val canApplyWhileRunning: Boolean? = null,
    val canChangeModel: Boolean? = null,
    val canChangeReasoning: Boolean? = null,
) {
    companion object {
        val Unavailable = RuntimeControlsCapabilities(
            available = false,
        )
    }
}

data class RuntimeControlsInfo(
    val runtimeSessionId: String? = null,
    val storedSessionId: String? = null,
    val profile: String? = null,
    val provider: String? = null,
    val model: String? = null,
    val reasoningEffort: String? = null,
    val reasoningEnabled: Boolean? = null,
    val running: Boolean? = null,
    val pendingModelSwitch: Boolean? = null,
    val authoritative: Boolean = false,
)

data class RuntimeControlsSnapshot(
    val origin: String,
    val profile: String,
    val storedSessionId: String,
    val runtimeSessionId: String,
    val provider: String? = null,
    val model: String? = null,
    val reasoningEffort: String? = null,
    val reasoningEnabled: Boolean? = null,
    val running: Boolean? = null,
    val capabilities: RuntimeControlsCapabilities = RuntimeControlsCapabilities.Unavailable,
    val source: RuntimeControlsSource = RuntimeControlsSource.ResumedSnapshot,
)

data class RuntimeControlsDraft(
    val origin: String,
    val profile: String,
    val storedSessionId: String,
    val runtimeSessionId: String,
    val provider: String?,
    val model: String?,
    val reasoningEffort: String?,
)

data class RuntimeControlsApplyResult(
    val deferred: Boolean = false,
    val acknowledged: Boolean = true,
    val authoritativeInfo: RuntimeControlsInfo? = null,
    val writes: List<RuntimeControlsWriteOutcome> = emptyList(),
) {
    val partial: Boolean
        get() = writes.any { it.status == RuntimeControlsWriteStatus.Rejected } &&
            writes.any { it.status != RuntimeControlsWriteStatus.Rejected }
}

class RuntimeControlsPartialApplyException(
    val completed: RuntimeControlsApplyResult,
    cause: Throwable,
) : IOException("Hermes applied only part of the conversation control change.", cause)

internal fun decodeRuntimeControlsCapabilities(element: JsonElement): RuntimeControlsCapabilities {
    val root = element as? JsonObject ?: return RuntimeControlsCapabilities.Unavailable
    val providers = root["providers"] as? JsonArray ?: return RuntimeControlsCapabilities.Unavailable
    val options = providers.flatMap { providerElement ->
        val provider = providerElement as? JsonObject
            ?: return@flatMap emptyList<RuntimeModelOption>()
        val providerId = provider.safeString("slug")
            ?: provider.safeString("id")
            ?: provider.safeString("name")
            ?: return@flatMap emptyList<RuntimeModelOption>()
        val models = provider["models"] as? JsonArray ?: return@flatMap emptyList<RuntimeModelOption>()
        val providerCapabilities = provider["capabilities"] as? JsonObject
        models.mapNotNull { modelElement ->
            val modelObject = modelElement as? JsonObject
            val model = (modelElement as? JsonPrimitive)
                ?.takeIf(JsonPrimitive::isString)
                ?.contentOrNull
                ?.takeIf(String::isNotBlank)
                ?: modelObject?.safeString("slug")
                ?: modelObject?.safeString("id")
                ?: modelObject?.safeString("name")
                ?: return@mapNotNull null
            val capability = (modelObject?.get("capabilities") as? JsonObject)
                ?: (providerCapabilities?.get(model) as? JsonObject)
            RuntimeModelOption(
                provider = providerId,
                model = model,
                supportsReasoning = capability?.safeBoolean("reasoning")
                    ?: modelObject?.safeBoolean("reasoning")
                    ?: provider.safeBoolean("reasoning")
                    ?: false,
                supportsFast = capability?.safeBoolean("fast")
                    ?: modelObject?.safeBoolean("fast")
                    ?: provider.safeBoolean("fast")
                    ?: false,
            )
        }
    }.distinctBy { it.provider to it.model }
    if (options.isEmpty()) return RuntimeControlsCapabilities.Unavailable

    val reasoningObject = root["reasoning"] as? JsonObject
    val efforts = sequenceOf(
        root["reasoning_efforts"],
        root["efforts"],
        reasoningObject?.get("efforts"),
    ).filterIsInstance<JsonArray>().firstOrNull()
        ?.mapNotNull { value ->
            (value as? JsonPrimitive)
                ?.takeIf(JsonPrimitive::isString)
                ?.contentOrNull
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.lowercase()
        }
        ?.distinct()
        .orEmpty()
    return RuntimeControlsCapabilities(
        available = true,
        modelOptions = options,
        reasoningEfforts = efforts,
        canApplyWhileRunning = root.safeBoolean("can_apply_while_running")
            ?: root.safeBoolean("supports_deferred_apply"),
        canChangeModel = root.safeBoolean("can_change_model")
            ?: root.safeBoolean("supports_model_change")
            ?: (root["mutability"] as? JsonObject)?.safeBoolean("model"),
        canChangeReasoning = root.safeBoolean("can_change_reasoning")
            ?: root.safeBoolean("supports_reasoning_change")
            ?: (root["mutability"] as? JsonObject)?.safeBoolean("reasoning"),
    )
}

internal fun decodeRuntimeControlsInfo(
    result: JsonObject,
    authoritative: Boolean = false,
): RuntimeControlsInfo {
    val info = result["info"] as? JsonObject ?: result["snapshot"] as? JsonObject
    val reasoning = result["reasoning"] as? JsonObject ?: info?.get("reasoning") as? JsonObject
    fun pickString(vararg keys: String): String? = keys.asSequence()
        .mapNotNull { key -> result.safeString(key) ?: info?.safeString(key) }
        .firstOrNull(String::isNotBlank)
    fun pickBoolean(vararg keys: String): Boolean? = keys.asSequence()
        .mapNotNull { key -> result.safeBoolean(key) ?: info?.safeBoolean(key) }
        .firstOrNull()
    fun pickPendingModelSwitch(): Boolean? = sequenceOf(result, info)
        .mapNotNull { value -> value?.pendingModelSwitch() }
        .firstOrNull()
    return RuntimeControlsInfo(
        runtimeSessionId = pickString("session_id", "runtime_session_id"),
        storedSessionId = pickString("resumed", "stored_session_id", "session_key"),
        profile = pickString("profile", "profile_id", "profile_name"),
        provider = pickString("provider", "model_provider")
            ?: reasoning?.safeString("provider"),
        model = pickString("model", "model_id"),
        reasoningEffort = pickString("reasoning_effort", "effort")
            ?: reasoning?.safeString("effort")
            ?: reasoning?.safeString("reasoning_effort"),
        reasoningEnabled = pickBoolean("reasoning_enabled", "reasoning_present")
            ?: reasoning?.safeBoolean("enabled"),
        running = pickBoolean("running", "busy"),
        pendingModelSwitch = pickPendingModelSwitch(),
        authoritative = authoritative,
    )
}

private fun JsonObject.safeString(key: String): String? =
    (this[key] as? JsonPrimitive)
        ?.takeIf(JsonPrimitive::isString)
        ?.contentOrNull
        ?.trim()
        ?.takeIf(String::isNotEmpty)

private fun JsonObject.safeBoolean(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.pendingModelSwitch(): Boolean? {
    val value = this["pending_model_switch"] ?: this["model_switch_pending"] ?: return null
    return when (value) {
        JsonNull -> false
        is JsonObject, is JsonArray -> true
        is JsonPrimitive -> value.booleanOrNull
            ?: value.contentOrNull?.let { text ->
                when {
                    text.equals("true", ignoreCase = true) -> true
                    text.equals("false", ignoreCase = true) -> false
                    else -> null
                }
            }
        else -> null
    }
}

private fun decodeConfigModel(value: String): Pair<String?, String?> {
    val provider = Regex("--provider\\s+([^\\s]+)").find(value)?.groupValues?.getOrNull(1)
    val model = value.substringBefore(" --").trim().takeIf(String::isNotBlank)
    return model to provider
}

internal fun decodeRuntimeControlsConfig(
    element: JsonElement,
    runtimeSessionId: String,
): RuntimeControlsInfo {
    val root = element as? JsonObject ?: return RuntimeControlsInfo()
    val metadata = root["meta"] as? JsonObject
    val scope = root.safeString("scope") ?: metadata?.safeString("scope")
    val responseRuntimeId = root.safeString("session_id")
        ?: root.safeString("runtime_session_id")
        ?: metadata?.safeString("session_id")
    val sessionScoped = scope?.lowercase() in setOf("session", "runtime", "conversation") ||
        root.safeBoolean("session_scoped") == true ||
        metadata?.safeBoolean("session_scoped") == true
    if (!sessionScoped) return RuntimeControlsInfo()

    val key = root.safeString("key") ?: metadata?.safeString("key")
    val value = root["value"]
    val valueObject = value as? JsonObject
    val valueText = (value as? JsonPrimitive)
        ?.takeIf(JsonPrimitive::isString)
        ?.contentOrNull
    val modelFromValue = valueText?.let(::decodeConfigModel)
    val model = valueObject?.safeString("model")
        ?: root.safeString("model")
        ?: modelFromValue?.first
    val provider = valueObject?.safeString("provider")
        ?: root.safeString("provider")
        ?: modelFromValue?.second
    val reasoning = valueObject?.safeString("reasoning_effort")
        ?: valueObject?.safeString("effort")
        ?: root.safeString("reasoning_effort")
        ?: root.safeString("effort")
        ?: valueText?.takeIf { key.equals("reasoning", ignoreCase = true) }
    val enabled = valueObject?.safeBoolean("enabled") ?: root.safeBoolean("reasoning_enabled")
    return RuntimeControlsInfo(
        runtimeSessionId = responseRuntimeId ?: runtimeSessionId,
        storedSessionId = root.safeString("stored_session_id") ?: metadata?.safeString("stored_session_id"),
        profile = root.safeString("profile") ?: metadata?.safeString("profile"),
        provider = provider,
        model = model,
        reasoningEffort = reasoning,
        reasoningEnabled = enabled,
        authoritative = model != null || provider != null || reasoning != null || enabled != null,
    )
}

private fun mergeRuntimeControlsInfo(
    first: RuntimeControlsInfo,
    second: RuntimeControlsInfo,
): RuntimeControlsInfo = RuntimeControlsInfo(
    runtimeSessionId = second.runtimeSessionId ?: first.runtimeSessionId,
    storedSessionId = second.storedSessionId ?: first.storedSessionId,
    profile = second.profile ?: first.profile,
    provider = second.provider ?: first.provider,
    model = second.model ?: first.model,
    reasoningEffort = second.reasoningEffort ?: first.reasoningEffort,
    reasoningEnabled = second.reasoningEnabled ?: first.reasoningEnabled,
    running = second.running ?: first.running,
    pendingModelSwitch = second.pendingModelSwitch ?: first.pendingModelSwitch,
    authoritative = first.authoritative || second.authoritative,
)

suspend fun GatewayConnection.readRuntimeControlsConfig(
    runtimeSessionId: String,
): RuntimeControlsInfo {
    require(runtimeSessionId.isNotBlank()) { "No Hermes conversation is open." }
    var result = RuntimeControlsInfo(runtimeSessionId = runtimeSessionId)
    for (key in listOf("model", "reasoning")) {
        val response = try {
            request(
                method = "config.get",
                params = buildJsonObject {
                    put("key", key)
                    put("session_id", runtimeSessionId)
                },
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            continue
        }
        result = mergeRuntimeControlsInfo(
            result,
            decodeRuntimeControlsConfig(response, runtimeSessionId),
        )
    }
    return result
}

private fun decodeRuntimeControlsApplyResult(
    element: JsonElement,
    key: String,
): RuntimeControlsApplyResult {
    val result = element as? JsonObject
        ?: throw IOException("Hermes returned no control acknowledgement.")
    val info = if (
        result["info"] is JsonObject ||
        result["snapshot"] is JsonObject ||
        listOf("model", "provider", "reasoning_effort", "running").any(result::containsKey)
    ) {
        decodeRuntimeControlsInfo(result, authoritative = true)
    } else {
        null
    }
    val deferred = result.safeBoolean("deferred") == true ||
        result.safeString("status")?.equals("deferred", ignoreCase = true) == true
    val acknowledged = result.safeBoolean("accepted") != false && result.safeBoolean("ok") != false
    return RuntimeControlsApplyResult(
        deferred = deferred,
        acknowledged = acknowledged,
        authoritativeInfo = info,
        writes = listOf(
            RuntimeControlsWriteOutcome(
                key = result.safeString("key") ?: key,
                status = when {
                    !acknowledged -> RuntimeControlsWriteStatus.Rejected
                    deferred -> RuntimeControlsWriteStatus.Deferred
                    else -> RuntimeControlsWriteStatus.Accepted
                },
                authoritativeInfo = info,
            ),
        ),
    )
}

suspend fun GatewayConnection.readRuntimeControlsOptions(
    runtimeSessionId: String,
): RuntimeControlsCapabilities {
    require(runtimeSessionId.isNotBlank()) { "No Hermes conversation is open." }
    return decodeRuntimeControlsCapabilities(
        request(
            method = "model.options",
            params = buildJsonObject { put("session_id", runtimeSessionId) },
        ),
    )
}

suspend fun GatewayConnection.applyRuntimeControls(
    runtimeSessionId: String,
    provider: String?,
    model: String?,
    reasoningEffort: String?,
    applyModel: Boolean,
    applyReasoning: Boolean,
): RuntimeControlsApplyResult {
    require(runtimeSessionId.isNotBlank()) { "No Hermes conversation is open." }
    var result = RuntimeControlsApplyResult()
    if (applyModel) {
        val selectedModel = model?.trim()?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Choose a supported model.")
        val selectedProvider = provider?.trim().orEmpty()
        val value = buildString {
            append(selectedModel)
            if (selectedProvider.isNotBlank()) append(" --provider ").append(selectedProvider)
            append(" --session")
        }
        result = decodeRuntimeControlsApplyResult(
            request(
                method = "config.set",
                params = buildJsonObject {
                    put("key", "model")
                    put("value", value)
                    put("session_id", runtimeSessionId)
                },
            ),
            key = "model",
        )
        if (!result.acknowledged) return result
    }
    if (applyReasoning) {
        val selectedEffort = reasoningEffort?.trim()?.lowercase()
            ?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Choose a supported reasoning setting.")
        try {
            val reasoningResult = decodeRuntimeControlsApplyResult(
                request(
                    method = "config.set",
                    params = buildJsonObject {
                        put("key", "reasoning")
                        put("value", selectedEffort)
                        put("session_id", runtimeSessionId)
                    },
                ),
                key = "reasoning",
            )
            if (!reasoningResult.acknowledged) {
                return RuntimeControlsApplyResult(
                    deferred = result.deferred || reasoningResult.deferred,
                    acknowledged = false,
                    authoritativeInfo = reasoningResult.authoritativeInfo ?: result.authoritativeInfo,
                    writes = result.writes + reasoningResult.writes,
                )
            }
            result = RuntimeControlsApplyResult(
                deferred = result.deferred || reasoningResult.deferred,
                acknowledged = result.acknowledged && reasoningResult.acknowledged,
                authoritativeInfo = reasoningResult.authoritativeInfo ?: result.authoritativeInfo,
                writes = result.writes + reasoningResult.writes,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw RuntimeControlsPartialApplyException(result, error)
        }
    }
    return result
}

suspend fun GatewayConnection.createSession(profile: String): CreatedSession {
    val selectedProfile = profile.trim().ifEmpty { "default" }
    val result = request(
        method = "session.create",
        params = buildJsonObject {
            put("cols", 96)
            put("source", "android")
            put("profile", selectedProfile)
        },
        timeoutMillis = 30_000,
    ).asObject("Hermes returned no created session.")
    val runtimeId = result.string("session_id")
        ?.takeIf(String::isNotBlank)
        ?: throw IOException("Hermes created a conversation without a runtime identity.")
    val info = result["info"] as? JsonObject
    return CreatedSession(
        runtimeSessionId = runtimeId,
        storedSessionId = result.string("stored_session_id")
            ?.takeIf(String::isNotBlank)
            ?: runtimeId,
        profile = result.string("profile")
            ?: result.string("profile_id")
            ?: info?.string("profile_name"),
        runtimeControls = decodeRuntimeControlsInfo(result, authoritative = true),
    )
}

suspend fun GatewayConnection.resumeStoredSession(storedSessionId: String): ResumedSession {
    require(storedSessionId.isNotBlank()) { "Choose a Hermes session to open." }
    val result = request(
        method = "session.resume",
        params = buildJsonObject {
            put("session_id", storedSessionId)
            put("cols", 96)
            put("source", "android")
        },
        timeoutMillis = 30_000,
    ).asObject("Hermes returned no resumed session.")

    val runtimeId = result.string("session_id")
        ?.takeIf(String::isNotBlank)
        ?: throw IOException("Hermes returned no runtime session identity.")
    val info = result["info"] as? JsonObject
    val running = result.boolean("running") ?: info?.boolean("running")
    val status = result.string("status") ?: info?.string("status")
    val inflight = result["inflight"]
    val queued = result["queued"]

    return ResumedSession(
        runtimeSessionId = runtimeId,
        storedSessionId = result.string("resumed")
            ?: result.string("stored_session_id")
            ?: result.string("session_key")
            ?: storedSessionId,
        messages = decodeGatewayMessages(result["messages"]?.jsonArray.orEmpty()),
        running = running,
        status = status,
        inflightAssistantText = inflightAssistantText(inflight),
        hasLiveProjection = inflight.isTruthy() || queued.isTruthy(),
        runtimeControls = decodeRuntimeControlsInfo(result, authoritative = true),
    )
}

suspend fun GatewayConnection.submitPrompt(runtimeSessionId: String, text: String): JsonObject {
    require(runtimeSessionId.isNotBlank()) { "No Hermes conversation is open." }
    require(text.isNotBlank()) { "Write a message first." }
    return request(
        method = "prompt.submit",
        params = buildJsonObject {
            put("session_id", runtimeSessionId)
            put("text", text)
        },
        timeoutMillis = 180_000,
    ).asObject("Hermes returned no prompt status.")
}

suspend fun GatewayConnection.interruptSession(runtimeSessionId: String): JsonObject {
    require(runtimeSessionId.isNotBlank()) { "No Hermes conversation is open." }
    return request(
        method = "session.interrupt",
        params = buildJsonObject { put("session_id", runtimeSessionId) },
    ).asObject("Hermes returned no interrupt status.")
}

internal fun decodeGatewayMessages(elements: List<JsonElement>): List<ConversationMessage> {
    val usedIds = mutableSetOf<String>()
    return elements.mapIndexedNotNull { index, element ->
        val message = decodeGatewayMessage(element) ?: return@mapIndexedNotNull null
        val preferredId = message.id?.takeIf(String::isNotBlank)
        val id = if (preferredId != null && usedIds.add(preferredId)) {
            preferredId
        } else {
            generateSequence("resume-$index") { current -> "$current-duplicate" }
                .first(usedIds::add)
        }
        message.copy(id = id)
    }
}

private fun decodeGatewayMessage(element: JsonElement): ConversationMessage? {
    val row = element as? JsonObject ?: return null
    val role = row.string("role")?.takeIf(String::isNotBlank) ?: return null
    val toolName = row.string("name") ?: row.string("tool_name")
    val text = row.string("text")
        ?: row.string("content")
        ?: row.string("context")
        ?: if (role == "tool") toolName.orEmpty() else ""
    return ConversationMessage(
        role = role,
        text = text,
        toolName = toolName,
        id = row["row_id"].scalarIdentity()?.let { "row-$it" }
            ?: row["id"].scalarIdentity()
            ?: row["message_id"].scalarIdentity(),
    )
}

private fun JsonElement?.scalarIdentity(): String? =
    (this as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)

private fun inflightAssistantText(element: JsonElement?): String {
    val row = element as? JsonObject ?: return ""
    return sequenceOf("assistant", "text", "content")
        .mapNotNull(row::string)
        .firstOrNull(String::isNotBlank)
        .orEmpty()
}

private fun JsonElement?.isTruthy(): Boolean = when (this) {
    null, JsonNull -> false
    is JsonObject, is JsonArray -> true
    is JsonPrimitive -> booleanOrNull
        ?: contentOrNull?.let { value ->
            value.isNotEmpty() && value != "0" && !value.equals("false", ignoreCase = true)
        }
        ?: false
}

private fun JsonElement.asObject(errorMessage: String): JsonObject =
    this as? JsonObject ?: throw IOException(errorMessage)

internal fun JsonObject.string(key: String): String? =
    get(key)?.jsonPrimitive?.contentOrNull

internal fun JsonObject.boolean(key: String): Boolean? =
    get(key)?.jsonPrimitive?.booleanOrNull
