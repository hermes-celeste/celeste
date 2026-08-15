package dev.hazydreams.hermesceleste.network

import java.time.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** The origin/profile/session chain that authorizes an activity projection. */
typealias NormalizedDashboardOrigin = String

enum class ActivitySource {
    Live,
    Resumed,
    Legacy,
    Unavailable,
}

enum class ActivityCapabilityState {
    Unknown,
    ToolOnly,
    ToolAndServerReasoning,
    LegacyToolOnly,
    Unsupported,
    Stale,
}

enum class ActivityPresentationState {
    Unknown,
    Discovering,
    Available,
    Running,
    Stale,
    Restoring,
    Unavailable,
}

enum class ToolPhase {
    Started,
    Running,
    Completed,
    Failed,
    Interrupted,
}

enum class CorrelationQuality {
    ExactId,
    LegacyName,
    Uncorrelated,
}

enum class ReasoningSource {
    ServerSummary,
    ServerFull,
}

enum class ReasoningPhase {
    Streaming,
    Complete,
    Unavailable,
}

data class DisplayedDetail(
    val text: String,
    val originalLength: Int,
    val wasTruncated: Boolean,
    val wasRedacted: Boolean,
    val canRestore: Boolean,
)

sealed interface ActivityItem {
    val uiKey: String
}

data class ToolActivity(
    override val uiKey: String,
    val callId: String?,
    val name: String,
    val phase: ToolPhase,
    val input: DisplayedDetail?,
    val output: DisplayedDetail?,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val correlation: CorrelationQuality,
) : ActivityItem

data class ServerReasoningActivity(
    override val uiKey: String,
    val source: ReasoningSource,
    val phase: ReasoningPhase,
    val text: DisplayedDetail,
    val serverLabel: String?,
) : ActivityItem

data class ActivityBinding(
    val originKey: NormalizedDashboardOrigin,
    val profile: String,
    val storedSessionId: String,
    val runtimeSessionId: String? = null,
)

data class AgentActivityProjection(
    val originKey: NormalizedDashboardOrigin,
    val profile: String,
    val storedSessionId: String,
    val runtimeSessionId: String?,
    val items: List<ActivityItem>,
    val source: ActivitySource,
    val capability: ActivityCapabilityState,
    val lastAuthoritativeSnapshot: Instant?,
    val presentation: ActivityPresentationState = ActivityPresentationState.Unknown,
    val malformedEventCount: Int = 0,
    val ambiguousCorrelationCount: Int = 0,
) {
    /** Count-only diagnostics; raw event payloads are deliberately absent. */
    val diagnosticCount: Int get() = malformedEventCount + ambiguousCorrelationCount

    companion object {
        fun forSession(
            originKey: NormalizedDashboardOrigin,
            profile: String,
            storedSessionId: String,
            runtimeSessionId: String? = null,
            capability: ActivityCapabilityState = ActivityCapabilityState.Unknown,
        ): AgentActivityProjection = AgentActivityProjection(
            originKey = normalizeActivityOrigin(originKey),
            profile = profile.ifBlank { "default" },
            storedSessionId = storedSessionId,
            runtimeSessionId = runtimeSessionId,
            items = emptyList(),
            source = ActivitySource.Unavailable,
            capability = capability,
            lastAuthoritativeSnapshot = null,
            presentation = if (capability == ActivityCapabilityState.Unsupported) {
                ActivityPresentationState.Unavailable
            } else {
                ActivityPresentationState.Discovering
            },
        )
    }
}

internal const val TOOL_ACTIVITY_DETAIL_LIMIT = 8_000
internal const val REASONING_ACTIVITY_DETAIL_LIMIT = 12_000
internal const val MAX_ACTIVITY_ITEMS = 100

/**
 * A pure redaction/truncation boundary for anything that can reach Compose or
 * clipboard. It intentionally keeps only the safe display prefix.
 */
fun sanitizeActivityDetail(
    raw: String,
    maxCodePoints: Int,
): DisplayedDetail {
    require(maxCodePoints > 0) { "Activity detail limit must be positive." }

    val originalLength = raw.codePointCount(0, raw.length)
    val redacted = redactActivitySecrets(raw)
    val wasRedacted = redacted != raw
    val redactedLength = redacted.codePointCount(0, redacted.length)
    if (redactedLength <= maxCodePoints) {
        return DisplayedDetail(
            text = redacted,
            originalLength = originalLength,
            wasTruncated = false,
            wasRedacted = wasRedacted,
            canRestore = false,
        )
    }

    val suffix = " … truncated (original length: $originalLength chars)"
    val suffixLength = suffix.codePointCount(0, suffix.length)
    val truncatedText = if (suffixLength >= maxCodePoints) {
        takeCodePoints(suffix, maxCodePoints)
    } else {
        takeCodePoints(redacted, maxCodePoints - suffixLength) + suffix
    }
    return DisplayedDetail(
        text = truncatedText,
        originalLength = originalLength,
        wasTruncated = true,
        wasRedacted = wasRedacted,
        canRestore = false,
    )
}

fun sanitizeActivityText(raw: String, maxCodePoints: Int): String =
    sanitizeActivityDetail(raw, maxCodePoints).text

private val privateKeyPattern = Regex(
    "-----BEGIN [^-\\n]*PRIVATE KEY-----.*?-----END [^-\\n]*PRIVATE KEY-----",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val authorizationHeaderPattern = Regex(
    "(?im)(\\bauthorization\\s*[:=]\\s*(?:bearer|basic)\\s+)[^\\s,;]+",
)
private val bearerPattern = Regex("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]{8,}")
private val cookieHeaderPattern = Regex("(?im)(\\b(?:cookie|set-cookie)\\s*[:=]\\s*)[^\\r\\n]+")
private val credentialAssignmentPattern = Regex(
    "(?i)(\\b(?:api[_-]?key|access[_-]?token|refresh[_-]?token|auth[_-]?token|password|passwd|secret|private[_-]?key)\\s*[:=]\\s*[\\\"']?)[^\\\"'\\s,}&]+",
)
private val credentialQueryPattern = Regex(
    "(?i)([?&](?:api[_-]?key|access[_-]?token|refresh[_-]?token|auth[_-]?token|password|secret|key)=)[^&#\\s]+",
)
private val knownTokenPattern = Regex(
    "(?i)\\b(?:sk-[A-Za-z0-9_-]{12,}|gh[pousr]_[A-Za-z0-9]{12,}|xox[baprs]-[A-Za-z0-9-]{10,}|eyJ[A-Za-z0-9_-]{20,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,})\\b",
)

private fun redactActivitySecrets(raw: String): String {
    var safe = privateKeyPattern.replace(raw, "[redacted]")
    safe = authorizationHeaderPattern.replace(safe) { "${it.groupValues[1]}[redacted]" }
    safe = bearerPattern.replace(safe, "Bearer [redacted]")
    safe = cookieHeaderPattern.replace(safe) { "${it.groupValues[1]}[redacted]" }
    safe = credentialAssignmentPattern.replace(safe) { "${it.groupValues[1]}[redacted]" }
    safe = credentialQueryPattern.replace(safe) { "${it.groupValues[1]}[redacted]" }
    return knownTokenPattern.replace(safe, "[redacted]")
}

private fun takeCodePoints(value: String, count: Int): String {
    if (count <= 0 || value.isEmpty()) return ""
    if (value.codePointCount(0, value.length) <= count) return value
    return value.substring(0, value.offsetByCodePoints(0, count))
}

internal fun normalizeActivityOrigin(origin: String): NormalizedDashboardOrigin =
    origin.trim().trimEnd('/')

internal fun initialActivityProjection(
    originKey: String,
    profile: String,
    storedSessionId: String,
    runtimeSessionId: String? = null,
    capability: ActivityCapabilityState = ActivityCapabilityState.Unknown,
): AgentActivityProjection = AgentActivityProjection.forSession(
    originKey = normalizeActivityOrigin(originKey),
    profile = profile,
    storedSessionId = storedSessionId,
    runtimeSessionId = runtimeSessionId,
    capability = capability,
)

/**
 * Pure reducer for source-audited Hermes activity events.
 *
 * The event names below are verified against the installed Hermes gateway at
 * `tui_gateway/server.py`: tool lifecycle uses `tool.start`/`tool.complete`,
 * the explicit server summary uses `reasoning.available`, and the configured
 * server reasoning stream uses `reasoning.delta`. `thinking.delta` is an
 * internal compatibility signal and is intentionally not presented here.
 */
internal object AgentActivityReducer {
    private const val REASONING_SUMMARY_EVENT = "reasoning.available"
    private const val REASONING_DELTA_EVENT = "reasoning.delta"

    fun applyEvent(
        projection: AgentActivityProjection,
        event: GatewayEvent,
        reasoningEnabled: Boolean = true,
        now: Instant = Instant.now(),
    ): AgentActivityProjection {
        if (!accepts(projection, event)) return projection
        return when (event.type) {
            "tool.start" -> applyToolStart(projection, event.payload, legacy = false, now)
            "tool_call" -> applyToolStart(projection, event.payload, legacy = true, now)
            "tool.complete" -> applyToolComplete(projection, event.payload, legacy = false, now)
            "tool_result" -> applyToolComplete(projection, event.payload, legacy = true, now)
            REASONING_SUMMARY_EVENT -> {
                if (reasoningEnabled) applyReasoningSummary(projection, event.payload) else projection
            }
            REASONING_DELTA_EVENT -> {
                if (reasoningEnabled) applyReasoningDelta(projection, event.payload) else projection
            }
            "message.complete" -> settleReasoning(projection)
            "message.interrupted", "session.interrupted" -> markInterrupted(projection, now)
            else -> projection
        }
    }

    fun applySnapshot(
        projection: AgentActivityProjection,
        items: List<ActivityItem>,
        binding: ActivityBinding,
        running: Boolean,
        now: Instant = Instant.now(),
    ): AgentActivityProjection {
        if (!sameBinding(projection, binding)) {
            return projection.copy(
                malformedEventCount = (projection.malformedEventCount + 1).coerceAtMost(1_000),
            )
        }
        val rekeyed = items
            .takeLast(MAX_ACTIVITY_ITEMS)
            .mapIndexed { index, item ->
                normalizeSnapshotItem(item, projection.storedSessionId, index)
            }
        val capability = if (
            projection.capability == ActivityCapabilityState.ToolAndServerReasoning &&
                rekeyed.none { it is ServerReasoningActivity }
        ) {
            // A local disclosure choice may hide the already-proven reasoning
            // stream. Keep the capability fact so the control can be restored
            // without retaining the hidden body in memory.
            ActivityCapabilityState.ToolAndServerReasoning
        } else {
            capabilityForItems(rekeyed)
        }
        return projection.copy(
            originKey = normalizeActivityOrigin(binding.originKey),
            profile = binding.profile.ifBlank { "default" },
            storedSessionId = binding.storedSessionId,
            runtimeSessionId = binding.runtimeSessionId,
            items = rekeyed,
            source = ActivitySource.Resumed,
            capability = capability,
            lastAuthoritativeSnapshot = now,
            presentation = when {
                capability == ActivityCapabilityState.Unsupported -> ActivityPresentationState.Unavailable
                running || rekeyed.any(ActivityItem::isInFlight) -> ActivityPresentationState.Running
                rekeyed.isNotEmpty() -> ActivityPresentationState.Available
                capability == ActivityCapabilityState.ToolAndServerReasoning -> ActivityPresentationState.Available
                else -> ActivityPresentationState.Discovering
            },
        )
    }

    fun markRestoring(projection: AgentActivityProjection): AgentActivityProjection =
        projection.copy(presentation = ActivityPresentationState.Restoring)

    fun markStale(projection: AgentActivityProjection): AgentActivityProjection =
        projection.copy(
            capability = ActivityCapabilityState.Stale,
            presentation = ActivityPresentationState.Stale,
        )

    fun markUnavailable(projection: AgentActivityProjection): AgentActivityProjection =
        projection.copy(
            source = ActivitySource.Unavailable,
            capability = ActivityCapabilityState.Unsupported,
            presentation = ActivityPresentationState.Unavailable,
        )

    fun withoutServerReasoning(projection: AgentActivityProjection): AgentActivityProjection {
        val items = projection.items.filterNot { it is ServerReasoningActivity }
        return projection.copy(
            items = items,
            capability = if (projection.capability == ActivityCapabilityState.ToolAndServerReasoning) {
                ActivityCapabilityState.ToolAndServerReasoning
            } else {
                capabilityForItems(items)
            },
            presentation = when {
                projection.presentation == ActivityPresentationState.Stale -> ActivityPresentationState.Stale
                items.any(ActivityItem::isInFlight) -> ActivityPresentationState.Running
                items.isNotEmpty() -> ActivityPresentationState.Available
                projection.capability == ActivityCapabilityState.ToolAndServerReasoning ->
                    ActivityPresentationState.Available
                else -> ActivityPresentationState.Discovering
            },
        )
    }

    fun markInterrupted(
        projection: AgentActivityProjection,
        now: Instant = Instant.now(),
    ): AgentActivityProjection {
        val items = projection.items.map { item ->
            when {
                item is ToolActivity && item.phase.isInFlight() ->
                    item.copy(phase = ToolPhase.Interrupted, finishedAt = now)
                item is ServerReasoningActivity && item.phase == ReasoningPhase.Streaming ->
                    item.copy(phase = ReasoningPhase.Unavailable)
                else -> item
            }
        }
        return projection.copy(
            items = items,
            presentation = if (items.any(ActivityItem::isInFlight)) {
                ActivityPresentationState.Running
            } else {
                ActivityPresentationState.Available
            },
        )
    }

    private fun accepts(projection: AgentActivityProjection, event: GatewayEvent): Boolean {
        val runtime = projection.runtimeSessionId
        if (runtime == null && event.sessionId.isNotBlank()) return false
        if (runtime != null && event.sessionId.isNotBlank() && event.sessionId != runtime) return false
        if (event.originKey != null && normalizeActivityOrigin(event.originKey) != projection.originKey) return false
        if (event.profile != null && event.profile!!.isNotBlank() && event.profile != projection.profile) return false
        if (event.storedSessionId != null && event.storedSessionId != projection.storedSessionId) return false
        return true
    }

    private fun applyToolStart(
        projection: AgentActivityProjection,
        payload: JsonObject,
        legacy: Boolean,
        now: Instant,
    ): AgentActivityProjection {
        val rawName = payload.firstString("name", "tool_name")?.trim().orEmpty()
        if (rawName.isBlank()) return malformed(projection)
        val name = safeToolName(rawName)
        val callId = payload.activityCallId()
        val input = payload.detailFrom(
            keys = listOf("args_text", "context", "args"),
            limit = TOOL_ACTIVITY_DETAIL_LIMIT,
        )
        val items = projection.items.toMutableList()
        val activeIndex = callId?.let { id ->
            items.indexOfFirst { item -> item is ToolActivity && item.callId == id && item.phase.isInFlight() }
        } ?: -1
        if (activeIndex >= 0) {
            val existing = items[activeIndex] as ToolActivity
            items[activeIndex] = existing.copy(
                name = if (existing.name == "Tool activity") name else existing.name,
                phase = if (existing.phase.isTerminal()) existing.phase else ToolPhase.Started,
                input = input ?: existing.input,
                startedAt = existing.startedAt ?: now,
                correlation = if (legacy) CorrelationQuality.LegacyName else CorrelationQuality.ExactId,
            )
        } else if (callId != null && items.any { item -> item is ToolActivity && item.callId == callId }) {
            // A repeated start for a terminal server call is an idempotent replay.
            return projection
        } else {
            items += ToolActivity(
                uiKey = nextActivityKey(projection, items),
                callId = callId,
                name = name,
                phase = ToolPhase.Started,
                input = input,
                output = null,
                startedAt = now,
                finishedAt = null,
                correlation = if (legacy || callId == null) {
                    CorrelationQuality.LegacyName
                } else {
                    CorrelationQuality.ExactId
                },
            )
        }
        return projection.copy(
            items = items.takeLast(MAX_ACTIVITY_ITEMS),
            source = if (legacy) ActivitySource.Legacy else ActivitySource.Live,
            capability = capabilityWithTool(projection.capability, legacy),
            presentation = ActivityPresentationState.Running,
        )
    }

    private fun applyToolComplete(
        projection: AgentActivityProjection,
        payload: JsonObject,
        legacy: Boolean,
        now: Instant,
    ): AgentActivityProjection {
        val rawName = payload.firstString("name", "tool_name")?.trim().orEmpty()
        val name = safeToolName(rawName.ifBlank { "Tool activity" })
        val callId = payload.activityCallId()
        val output = payload.detailFrom(
            keys = listOf("result_text", "summary", "output", "result", "error"),
            limit = TOOL_ACTIVITY_DETAIL_LIMIT,
        )
        val input = payload.detailFrom(
            keys = listOf("args_text", "context", "args"),
            limit = TOOL_ACTIVITY_DETAIL_LIMIT,
        )
        val failed = payload.booleanLike("is_error", "failed") ||
            payload.firstString("status")?.lowercase() in setOf("error", "failed", "failure") ||
            payload.firstString("error")?.isNotBlank() == true
        val phase = if (failed) ToolPhase.Failed else ToolPhase.Completed
        val items = projection.items.toMutableList()
        val matchIndex = when {
            callId != null -> items.indexOfFirst { item ->
                item is ToolActivity && item.callId == callId && item.phase.isInFlight()
            }
            rawName.isNotBlank() -> {
                val candidates = items.withIndex().filter { (_, item) ->
                    item is ToolActivity && item.name == name && item.phase.isInFlight()
                }
                if (candidates.size == 1) candidates.single().index else -1
            }
            else -> -1
        }
        val ambiguousLegacy = callId == null && rawName.isNotBlank() &&
            items.count { item -> item is ToolActivity && item.name == name && item.phase.isInFlight() } > 1
        if (callId != null && items.any { item -> item is ToolActivity && item.callId == callId && item.phase.isTerminal() }) {
            // Retransmitted completion after a reconnect must not duplicate a card.
            return projection
        }
        if (matchIndex >= 0 && !ambiguousLegacy) {
            val existing = items[matchIndex] as ToolActivity
            items[matchIndex] = existing.copy(
                phase = phase,
                input = input ?: existing.input,
                output = output ?: existing.output,
                finishedAt = now,
                correlation = when {
                    callId != null && !legacy -> CorrelationQuality.ExactId
                    else -> CorrelationQuality.LegacyName
                },
            )
        } else {
            items += ToolActivity(
                uiKey = nextActivityKey(projection, items),
                callId = callId,
                name = name,
                phase = phase,
                input = input,
                output = output,
                startedAt = null,
                finishedAt = now,
                correlation = CorrelationQuality.Uncorrelated,
            )
        }
        return projection.copy(
            items = items.takeLast(MAX_ACTIVITY_ITEMS),
            source = if (legacy) ActivitySource.Legacy else ActivitySource.Live,
            capability = capabilityWithTool(projection.capability, legacy),
            presentation = if (items.any(ActivityItem::isInFlight)) {
                ActivityPresentationState.Running
            } else {
                ActivityPresentationState.Available
            },
            ambiguousCorrelationCount = if (ambiguousLegacy) {
                (projection.ambiguousCorrelationCount + 1).coerceAtMost(1_000)
            } else {
                projection.ambiguousCorrelationCount
            },
        )
    }

    private fun applyReasoningSummary(
        projection: AgentActivityProjection,
        payload: JsonObject,
    ): AgentActivityProjection {
        val detail = payload.detailFrom(listOf("text"), REASONING_ACTIVITY_DETAIL_LIMIT)
            ?: return malformed(projection)
        val item = ServerReasoningActivity(
            uiKey = nextActivityKey(projection, projection.items),
            source = ReasoningSource.ServerSummary,
            phase = ReasoningPhase.Complete,
            text = detail.copy(text = stripReasoningTags(detail.text)),
            serverLabel = safeServerLabel(payload.firstString("label")) ?: "Server-provided summary",
        )
        val items = projection.items.toMutableList()
        val existingIndex = items.indexOfLast {
            it is ServerReasoningActivity && it.source == ReasoningSource.ServerSummary
        }
        if (existingIndex >= 0) items[existingIndex] = item.copy(uiKey = items[existingIndex].uiKey) else items += item
        return projection.copy(
            items = items.takeLast(MAX_ACTIVITY_ITEMS),
            source = ActivitySource.Live,
            capability = ActivityCapabilityState.ToolAndServerReasoning,
            presentation = ActivityPresentationState.Available,
        )
    }

    private fun applyReasoningDelta(
        projection: AgentActivityProjection,
        payload: JsonObject,
    ): AgentActivityProjection {
        val detail = payload.detailFrom(listOf("text"), REASONING_ACTIVITY_DETAIL_LIMIT)
            ?: return malformed(projection)
        val delta = detail.copy(text = stripReasoningTags(detail.text))
        val items = projection.items.toMutableList()
        val index = items.indexOfLast {
            it is ServerReasoningActivity &&
                it.source == ReasoningSource.ServerFull &&
                it.phase == ReasoningPhase.Streaming
        }
        if (index >= 0) {
            val existing = items[index] as ServerReasoningActivity
            val combined = sanitizeActivityDetail(
                existing.text.text + delta.text,
                REASONING_ACTIVITY_DETAIL_LIMIT,
            )
            items[index] = existing.copy(
                text = combined.copy(
                    originalLength = existing.text.originalLength + delta.originalLength,
                    wasRedacted = existing.text.wasRedacted || delta.wasRedacted,
                ),
            )
        } else {
            items += ServerReasoningActivity(
                uiKey = nextActivityKey(projection, items),
                source = ReasoningSource.ServerFull,
                phase = ReasoningPhase.Streaming,
                text = delta,
                serverLabel = "Server-provided reasoning",
            )
        }
        return projection.copy(
            items = items.takeLast(MAX_ACTIVITY_ITEMS),
            source = ActivitySource.Live,
            capability = ActivityCapabilityState.ToolAndServerReasoning,
            presentation = ActivityPresentationState.Running,
        )
    }

    private fun settleReasoning(projection: AgentActivityProjection): AgentActivityProjection {
        val items = projection.items.map { item ->
            if (item is ServerReasoningActivity && item.phase == ReasoningPhase.Streaming) {
                item.copy(phase = ReasoningPhase.Complete)
            } else {
                item
            }
        }
        return projection.copy(
            items = items,
            presentation = if (items.any(ActivityItem::isInFlight)) {
                ActivityPresentationState.Running
            } else {
                ActivityPresentationState.Available
            },
        )
    }

    private fun malformed(projection: AgentActivityProjection): AgentActivityProjection =
        projection.copy(malformedEventCount = (projection.malformedEventCount + 1).coerceAtMost(1_000))

    private fun sameBinding(projection: AgentActivityProjection, binding: ActivityBinding): Boolean =
        normalizeActivityOrigin(binding.originKey) == projection.originKey &&
            binding.profile.ifBlank { "default" } == projection.profile &&
            binding.storedSessionId == projection.storedSessionId

    private fun normalizeSnapshotItem(
        item: ActivityItem,
        storedSessionId: String,
        index: Int,
    ): ActivityItem = when (item) {
        is ToolActivity -> item.copy(uiKey = "activity:$storedSessionId:snapshot:tool:$index")
        is ServerReasoningActivity -> item.copy(uiKey = "activity:$storedSessionId:snapshot:reasoning:$index")
    }

    private fun capabilityForItems(items: List<ActivityItem>): ActivityCapabilityState {
        val hasReasoning = items.any {
            it is ServerReasoningActivity && it.phase != ReasoningPhase.Unavailable
        }
        val tools = items.filterIsInstance<ToolActivity>()
        if (hasReasoning) return ActivityCapabilityState.ToolAndServerReasoning
        if (tools.isEmpty()) return ActivityCapabilityState.Unknown
        return if (tools.all { it.correlation != CorrelationQuality.ExactId }) {
            ActivityCapabilityState.LegacyToolOnly
        } else {
            ActivityCapabilityState.ToolOnly
        }
    }

    private fun capabilityWithTool(
        current: ActivityCapabilityState,
        legacy: Boolean,
    ): ActivityCapabilityState = when {
        current == ActivityCapabilityState.ToolAndServerReasoning -> current
        legacy -> ActivityCapabilityState.LegacyToolOnly
        else -> ActivityCapabilityState.ToolOnly
    }

    private fun nextActivityKey(
        projection: AgentActivityProjection,
        items: List<ActivityItem>,
    ): String {
        val prefix = "activity:${projection.storedSessionId.ifBlank { "session" }}:live:"
        var occurrence = items.size + 1
        var candidate = "$prefix$occurrence"
        val keys = items.asSequence().map(ActivityItem::uiKey).toSet()
        while (candidate in keys) {
            occurrence += 1
            candidate = "$prefix$occurrence"
        }
        return candidate
    }
}

internal fun reduceActivityEvent(
    projection: AgentActivityProjection,
    event: GatewayEvent,
    reasoningEnabled: Boolean = true,
    now: Instant = Instant.now(),
): AgentActivityProjection = AgentActivityReducer.applyEvent(projection, event, reasoningEnabled, now)

internal fun activityItemsFromMessages(messages: List<ConversationMessage>): List<ActivityItem> =
    messages.mapNotNull { message ->
        if (message.role != "tool") return@mapNotNull null
        val detail = message.text.takeIf(String::isNotBlank)?.let {
            sanitizeActivityDetail(it, TOOL_ACTIVITY_DETAIL_LIMIT)
        }
        ToolActivity(
            uiKey = "",
            callId = null,
            name = safeToolName(message.toolName ?: "Tool activity"),
            phase = if (message.pending) ToolPhase.Running else ToolPhase.Completed,
            input = if (message.pending) detail else null,
            output = if (message.pending) null else detail,
            startedAt = null,
            finishedAt = null,
            correlation = CorrelationQuality.LegacyName,
        )
    }

/** Decode only the fields the current Hermes session projection actually emits. */
internal fun decodeGatewayActivity(elements: List<JsonElement>): List<ActivityItem> =
    elements.mapNotNull { element ->
        val row = element as? JsonObject ?: return@mapNotNull null
        when (row.stringValue("role")) {
            "tool" -> {
                val rawName = row.firstString("name", "tool_name") ?: return@mapNotNull null
                val callId = row.activityCallId()
                val failed = row.booleanLike("is_error", "failed") ||
                    row.firstString("status")?.lowercase() in setOf("error", "failed", "failure")
                ToolActivity(
                    uiKey = "",
                    callId = callId,
                    name = safeToolName(rawName),
                    phase = if (failed) ToolPhase.Failed else ToolPhase.Completed,
                    input = row.detailFrom(listOf("args_text", "context", "args"), TOOL_ACTIVITY_DETAIL_LIMIT),
                    output = row.detailFrom(
                        listOf("result_text", "summary", "output", "result", "error"),
                        TOOL_ACTIVITY_DETAIL_LIMIT,
                    ),
                    startedAt = null,
                    finishedAt = null,
                    correlation = if (callId == null) {
                        CorrelationQuality.LegacyName
                    } else {
                        CorrelationQuality.ExactId
                    },
                )
            }
            "assistant" -> {
                val summary = row.detailFrom(listOf("reasoning"), REASONING_ACTIVITY_DETAIL_LIMIT)
                val full = row.detailFrom(listOf("reasoning_content"), REASONING_ACTIVITY_DETAIL_LIMIT)
                val detail = summary ?: full ?: return@mapNotNull null
                ServerReasoningActivity(
                    uiKey = "",
                    source = if (summary != null) ReasoningSource.ServerSummary else ReasoningSource.ServerFull,
                    phase = ReasoningPhase.Complete,
                    text = detail.copy(text = stripReasoningTags(detail.text)),
                    serverLabel = if (summary != null) {
                        "Server-provided summary"
                    } else {
                        "Server-provided reasoning"
                    },
                )
            }
            else -> null
        }
    }

private fun JsonObject.firstString(vararg keys: String): String? =
    keys.asSequence()
        .mapNotNull { key -> this[key]?.stringValue() }
        .firstOrNull { it.isNotBlank() }

private fun JsonObject.stringValue(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.activityCallId(): String? {
    val direct = sequenceOf("tool_call_id", "call_id", "id", "tool_id")
        .mapNotNull(::stringValue)
        .firstOrNull(String::isNotBlank)
    if (direct != null) return direct.trim()
    return sequenceOf("tool_call", "tool")
        .mapNotNull { key -> this[key] as? JsonObject }
        .flatMap { nested -> sequenceOf("tool_call_id", "call_id", "id", "tool_id").mapNotNull(nested::stringValue) }
        .firstOrNull(String::isNotBlank)
        ?.trim()
}

private fun JsonObject.booleanLike(vararg keys: String): Boolean =
    keys.any { key ->
        when (stringValue(key)?.lowercase()) {
            "true", "1", "yes", "failed", "error" -> true
            else -> false
        }
    }

private fun JsonObject.detailFrom(keys: List<String>, limit: Int): DisplayedDetail? {
    for (key in keys) {
        val value = this[key] ?: continue
        val text = value.displayText() ?: continue
        if (text.isBlank()) continue
        return sanitizeActivityDetail(stripReasoningTags(text), limit)
    }
    return null
}

private fun JsonElement.displayText(): String? = when (this) {
    JsonNull -> null
    is JsonPrimitive -> contentOrNull
    else -> toString()
}

private fun safeToolName(raw: String): String {
    val candidate = raw.trim()
    if (candidate.isBlank()) return "Tool activity"
    if (candidate.length > 80 || !candidate.matches(Regex("[A-Za-z0-9_.:-]+"))) {
        return "Tool activity"
    }
    return candidate.replace('_', ' ')
}

private fun safeServerLabel(raw: String?): String? {
    val candidate = raw?.trim().orEmpty()
    if (candidate.isBlank() || candidate.length > 80) return null
    return if (candidate.matches(Regex("[A-Za-z0-9 ._:-]+"))) candidate else null
}

private fun stripReasoningTags(text: String): String =
    text.replace(
        Regex("</?(?:REASONING_SCRATCHPAD|think|reasoning)>", RegexOption.IGNORE_CASE),
        "",
    ).trim()

private fun ToolPhase.isInFlight(): Boolean = this == ToolPhase.Started || this == ToolPhase.Running
private fun ToolPhase.isTerminal(): Boolean = !isInFlight()
private fun ActivityItem.isInFlight(): Boolean =
    this is ToolActivity && phase.isInFlight() ||
        this is ServerReasoningActivity && phase == ReasoningPhase.Streaming
