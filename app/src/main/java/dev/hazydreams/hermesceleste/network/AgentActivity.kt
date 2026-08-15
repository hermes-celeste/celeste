package dev.hazydreams.hermesceleste.network

import java.time.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
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
    /** The official `tool.progress` preview, kept separate from input/output. */
    val progress: DisplayedDetail? = null,
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
    /** Null means the server has not disclosed its reasoning display policy. */
    val serverReasoningAllowed: Boolean? = null,
    /** Event IDs/sequences only; never raw activity payloads. */
    internal val seenEventKeys: Set<String> = emptySet(),
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
private const val MAX_SEEN_ACTIVITY_EVENT_KEYS = 512

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
    "(?im)(\\b(?:authorization|proxy-authorization)\\s*[:=]\\s*(?:bearer|basic|token)\\s+)[^\\s,;]+",
)
private val authorizationValuePattern = Regex(
    "(?im)(\\b(?:authorization|proxy-authorization)\\s*[:=]\\s*)[^\\r\\n,;]+",
)
private val bearerPattern = Regex("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]+")
private val cookieHeaderPattern = Regex("(?im)(\\b(?:cookie|set-cookie)\\s*[:=]\\s*)[^\\r\\n]+")
private val sessionTokenHeaderPattern = Regex(
    "(?im)(\\b(?:x[-_]?hermes[-_])?session[-_]?token\\s*[:=]\\s*)[^\\s,;]+",
)
private val credentialAssignmentPattern = Regex(
    "(?i)(\\b(?:api[_-]?key|api[_-]?secret|access[_-]?token|refresh[_-]?token|auth[_-]?token|session[_-]?token|id[_-]?token|client[_-]?secret|password|passwd|secret|credential|private[_-]?key|token)\\s*[:=]\\s*[\\\"']?)[^\\\"'\\s,}&]+",
)
private val credentialQueryPattern = Regex(
    "(?i)([?&](?:api[_-]?key|api[_-]?secret|access[_-]?token|refresh[_-]?token|auth[_-]?token|session[_-]?token|id[_-]?token|client[_-]?secret|password|secret|credential|token|key)=)[^&#\\s]+",
)
private val knownTokenPattern = Regex(
    "(?i)\\b(?:sk-[A-Za-z0-9_-]{12,}|gh[pousr]_[A-Za-z0-9]{12,}|xox[baprs]-[A-Za-z0-9-]{10,}|eyJ[A-Za-z0-9_-]{20,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,})\\b",
)
private val hiddenReasoningTagPattern = Regex(
    "</?(?:think|thinking|reasoning|reasoning_scratchpad)\\b[^>]*>",
    RegexOption.IGNORE_CASE,
)

private fun redactActivitySecrets(raw: String): String {
    var safe = privateKeyPattern.replace(raw, "[redacted]")
    safe = authorizationHeaderPattern.replace(safe) { "${it.groupValues[1]}[redacted]" }
    safe = authorizationValuePattern.replace(safe) { "${it.groupValues[1]}[redacted]" }
    safe = bearerPattern.replace(safe, "Bearer [redacted]")
    safe = cookieHeaderPattern.replace(safe) { "${it.groupValues[1]}[redacted]" }
    safe = sessionTokenHeaderPattern.replace(safe) { "${it.groupValues[1]}[redacted]" }
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
        val replayKey = activityReplayKey(event)
        if (replayKey != null && replayKey in projection.seenEventKeys) return projection
        val updated = when (event.type) {
            "tool.start" -> applyToolStart(projection, event.payload, legacy = false, now)
            "tool_call" -> applyToolStart(projection, event.payload, legacy = true, now)
            "tool.progress" -> applyToolProgress(projection, event.payload, now)
            "tool.complete" -> applyToolComplete(projection, event.payload, legacy = false, now)
            "tool_result" -> applyToolComplete(projection, event.payload, legacy = true, now)
            REASONING_SUMMARY_EVENT -> {
                if (
                    reasoningEnabled &&
                    projection.serverReasoningAllowed != false &&
                    event.payload.booleanValue("verbose") != false
                ) {
                    applyReasoningSummary(projection, event.payload)
                } else {
                    projection
                }
            }
            REASONING_DELTA_EVENT -> {
                // Hermes marks the provider-facing reasoning callback with
                // `verbose: true`. An unmarked delta is not a user-facing
                // summary and must never become a CoT surface in Celeste.
                if (
                    reasoningEnabled &&
                    projection.serverReasoningAllowed != false &&
                    event.payload.booleanValue("verbose") == true
                ) {
                    applyReasoningDelta(projection, event.payload)
                } else {
                    projection
                }
            }
            "session.info" -> applySessionInfo(projection, event.payload)
            "message.complete" -> settleOpenItems(
                projection,
                failed = event.payload.isFailure(),
                now = now,
            )
            "message.error", "error" -> settleOpenItems(projection, failed = true, now = now)
            "message.interrupted", "session.interrupted" -> markInterrupted(projection, now)
            "tool.generating" -> projection.copy(presentation = ActivityPresentationState.Running)
            else -> {
                if (event.type.startsWith("tool.") || event.type.startsWith("reasoning.")) {
                    unavailableAfterMalformed(projection)
                } else {
                    projection
                }
            }
        }
        return if (replayKey == null) {
            updated
        } else {
            updated.copy(seenEventKeys = rememberReplayKey(projection.seenEventKeys, replayKey))
        }
    }

    fun applySnapshot(
        projection: AgentActivityProjection,
        items: List<ActivityItem>,
        binding: ActivityBinding,
        running: Boolean,
        serverReasoningAllowed: Boolean? = projection.serverReasoningAllowed,
        now: Instant = Instant.now(),
    ): AgentActivityProjection {
        if (!sameBinding(projection, binding)) {
            return projection.copy(
                malformedEventCount = (projection.malformedEventCount + 1).coerceAtMost(1_000),
            )
        }
        val visibleItems = items.filterNot {
            serverReasoningAllowed == false && it is ServerReasoningActivity
        }
        val rekeyed = normalizeActivityKeys(
            visibleItems.takeLast(MAX_ACTIVITY_ITEMS),
            projection.storedSessionId,
        )
        val capability = when {
            projection.capability == ActivityCapabilityState.Unsupported ->
                ActivityCapabilityState.Unsupported
            projection.capability == ActivityCapabilityState.ToolAndServerReasoning &&
                serverReasoningAllowed != false &&
                rekeyed.none { it is ServerReasoningActivity } -> {
                // A local disclosure choice may hide the already-proven reasoning
                // stream. Keep the capability fact so the control can be restored
                // without retaining the hidden body in memory.
                ActivityCapabilityState.ToolAndServerReasoning
            }
            projection.capability == ActivityCapabilityState.LegacyToolOnly ->
                ActivityCapabilityState.LegacyToolOnly
            projection.capability == ActivityCapabilityState.ToolOnly ->
                ActivityCapabilityState.ToolOnly
            else -> capabilityForItems(rekeyed)
        }
        return projection.copy(
            originKey = normalizeActivityOrigin(binding.originKey),
            profile = binding.profile.trim().ifBlank { "default" },
            storedSessionId = binding.storedSessionId.trim(),
            runtimeSessionId = binding.runtimeSessionId?.trim()?.takeIf(String::isNotBlank),
            items = rekeyed,
            source = when {
                capability == ActivityCapabilityState.Unsupported -> ActivitySource.Unavailable
                capability == ActivityCapabilityState.LegacyToolOnly -> ActivitySource.Legacy
                rekeyed.any { item ->
                    item is ToolActivity && item.correlation != CorrelationQuality.ExactId
                } -> ActivitySource.Legacy
                else -> ActivitySource.Resumed
            },
            capability = capability,
            serverReasoningAllowed = serverReasoningAllowed,
            lastAuthoritativeSnapshot = now,
            presentation = when {
                capability == ActivityCapabilityState.Unsupported -> ActivityPresentationState.Unavailable
                running || rekeyed.any(ActivityItem::isInFlight) -> ActivityPresentationState.Running
                rekeyed.isNotEmpty() -> ActivityPresentationState.Available
                capability != ActivityCapabilityState.Unknown &&
                    capability != ActivityCapabilityState.Stale -> ActivityPresentationState.Available
                else -> ActivityPresentationState.Discovering
            },
        )
    }

    /** Apply a server disclosure declaration without retaining a hidden body. */
    fun applyServerReasoningCapability(
        projection: AgentActivityProjection,
        allowed: Boolean,
    ): AgentActivityProjection {
        if (allowed) return projection.copy(serverReasoningAllowed = true)
        val items = projection.items.filterNot { it is ServerReasoningActivity }
        val capability = if (projection.capability == ActivityCapabilityState.ToolAndServerReasoning) {
            capabilityForItems(items)
        } else {
            projection.capability
        }
        return projection.copy(
            items = items,
            capability = capability,
            serverReasoningAllowed = false,
            presentation = when {
                projection.presentation == ActivityPresentationState.Stale -> ActivityPresentationState.Stale
                items.any(ActivityItem::isInFlight) -> ActivityPresentationState.Running
                items.isNotEmpty() -> ActivityPresentationState.Available
                capability == ActivityCapabilityState.Unsupported -> ActivityPresentationState.Unavailable
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

    /**
     * Resolve a completed resume with no activity evidence without leaving the
     * user at the indefinite discovery state. Explicit legacy/tool capability
     * remains truthful even when this particular session has no activity rows.
     */
    fun markAbsent(projection: AgentActivityProjection): AgentActivityProjection = when {
        projection.capability == ActivityCapabilityState.Unknown -> markUnavailable(projection)
        projection.capability == ActivityCapabilityState.LegacyToolOnly -> projection.copy(
            source = ActivitySource.Legacy,
            presentation = ActivityPresentationState.Available,
        )
        projection.capability == ActivityCapabilityState.Unsupported -> markUnavailable(projection)
        projection.presentation == ActivityPresentationState.Stale -> projection
        else -> projection.copy(
            source = ActivitySource.Resumed,
            presentation = ActivityPresentationState.Available,
        )
    }

    /** Settle cards when Hermes ends a turn without sending tool.complete. */
    fun settleOpenItems(
        projection: AgentActivityProjection,
        failed: Boolean,
        now: Instant = Instant.now(),
    ): AgentActivityProjection {
        val phase = if (failed) ToolPhase.Failed else ToolPhase.Interrupted
        val items = projection.items.map { item ->
            when {
                item is ToolActivity && item.phase.isInFlight() ->
                    item.copy(phase = phase, finishedAt = now)
                item is ServerReasoningActivity && item.phase == ReasoningPhase.Streaming ->
                    item.copy(phase = if (failed) ReasoningPhase.Unavailable else ReasoningPhase.Complete)
                else -> item
            }
        }
        return projection.copy(
            items = items,
            presentation = if (items.any(ActivityItem::isInFlight)) {
                ActivityPresentationState.Running
            } else if (projection.presentation == ActivityPresentationState.Unavailable) {
                ActivityPresentationState.Unavailable
            } else {
                ActivityPresentationState.Available
            },
        )
    }

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
        val runtime = projection.runtimeSessionId?.trim()?.takeIf(String::isNotBlank)
        val eventSession = event.sessionId.trim()
        if (runtime == null && eventSession.isNotBlank()) return false
        if (runtime != null && eventSession.isNotBlank() && eventSession != runtime) return false
        if (event.originKey != null && normalizeActivityOrigin(event.originKey) != projection.originKey) return false
        if (
            event.profile != null &&
            event.profile!!.trim().isNotBlank() &&
            event.profile!!.trim() != projection.profile
        ) return false
        if (
            event.storedSessionId != null &&
            event.storedSessionId!!.trim().isNotBlank() &&
            event.storedSessionId!!.trim() != projection.storedSessionId
        ) return false
        return true
    }

    private fun applyToolStart(
        projection: AgentActivityProjection,
        payload: JsonObject,
        legacy: Boolean,
        now: Instant,
    ): AgentActivityProjection {
        val rawName = payload.firstString("name", "tool_name")?.trim().orEmpty()
        if (rawName.isBlank()) return unavailableAfterMalformed(projection)
        val name = safeToolName(rawName, payload.toolNameIsUnsafe())
        val callId = payload.activityCallId()
        val effectiveLegacy = legacy || callId == null
        val input = payload.detailFrom(
            keys = listOf("args_text", "context", "args"),
            limit = TOOL_ACTIVITY_DETAIL_LIMIT,
        )
        val items = projection.items.toMutableList()
        val activeCandidates = if (callId == null) {
            items.withIndex().filter { (_, item) ->
                item is ToolActivity && item.name == name && item.phase.isInFlight()
            }
        } else {
            items.withIndex().filter { (_, item) ->
                item is ToolActivity && item.callId == callId && item.phase.isInFlight()
            }
        }
        val activeIndex = if (callId != null) {
            activeCandidates.singleOrNull()?.index ?: -1
        } else {
            -1
        }
        if (activeIndex >= 0) {
            val existing = items[activeIndex] as ToolActivity
            // A repeated start for the same active occurrence is a harmless
            // replay. A changed payload is allowed to create a new occurrence.
            if (callId != null && existing.name == name && (input == null || input == existing.input)) {
                return projection
            }
            items[activeIndex] = existing.copy(
                name = if (existing.name == "Tool activity") name else existing.name,
                phase = ToolPhase.Started,
                input = input ?: existing.input,
                startedAt = existing.startedAt ?: now,
                correlation = if (effectiveLegacy) {
                    CorrelationQuality.LegacyName
                } else {
                    CorrelationQuality.ExactId
                },
            )
        } else {
            items += ToolActivity(
                uiKey = nextActivityKey(projection, items, callId, name),
                callId = callId,
                name = name,
                phase = ToolPhase.Started,
                input = input,
                output = null,
                startedAt = now,
                finishedAt = null,
                correlation = if (effectiveLegacy) {
                    CorrelationQuality.LegacyName
                } else {
                    CorrelationQuality.ExactId
                },
            )
        }
        return projection.copy(
            items = items.takeLast(MAX_ACTIVITY_ITEMS),
            source = if (effectiveLegacy) ActivitySource.Legacy else ActivitySource.Live,
            capability = capabilityWithTool(projection.capability, effectiveLegacy),
            presentation = ActivityPresentationState.Running,
        )
    }

    private fun applyToolProgress(
        projection: AgentActivityProjection,
        payload: JsonObject,
        now: Instant,
    ): AgentActivityProjection {
        val rawName = payload.firstString("name", "tool_name")?.trim().orEmpty()
        val callId = payload.activityCallId()
        val progress = payload.detailFrom(
            keys = listOf("preview", "progress", "delta", "text", "context"),
            limit = TOOL_ACTIVITY_DETAIL_LIMIT,
        ) ?: return unavailableAfterMalformed(projection)
        val fallbackName = if (rawName.isBlank()) {
            "Tool activity"
        } else {
            safeToolName(rawName, payload.toolNameIsUnsafe())
        }
        val items = projection.items.toMutableList()
        val candidates = items.withIndex().filter { (_, item) ->
            item is ToolActivity &&
                item.phase.isInFlight() &&
                if (callId != null) item.callId == callId else item.name == fallbackName
        }
        val ambiguous = candidates.size > 1
        if (candidates.size == 1) {
            val index = candidates.single().index
            val existing = items[index] as ToolActivity
            if (existing.progress == progress) return projection
            items[index] = existing.copy(
                phase = ToolPhase.Running,
                progress = progress,
                startedAt = existing.startedAt ?: now,
                correlation = if (callId == null) {
                    CorrelationQuality.LegacyName
                } else {
                    CorrelationQuality.ExactId
                },
            )
        } else {
            items += ToolActivity(
                uiKey = nextActivityKey(projection, items, callId, fallbackName),
                callId = callId,
                name = fallbackName,
                phase = ToolPhase.Running,
                input = null,
                output = null,
                startedAt = now,
                finishedAt = null,
                correlation = when {
                    ambiguous -> CorrelationQuality.Uncorrelated
                    callId == null -> CorrelationQuality.LegacyName
                    else -> CorrelationQuality.ExactId
                },
                progress = progress,
            )
        }
        return projection.copy(
            items = items.takeLast(MAX_ACTIVITY_ITEMS),
            source = if (callId == null) ActivitySource.Legacy else ActivitySource.Live,
            capability = capabilityWithTool(projection.capability, legacy = callId == null),
            presentation = ActivityPresentationState.Running,
            ambiguousCorrelationCount = if (ambiguous) {
                (projection.ambiguousCorrelationCount + 1).coerceAtMost(1_000)
            } else {
                projection.ambiguousCorrelationCount
            },
        )
    }

    private fun applyToolComplete(
        projection: AgentActivityProjection,
        payload: JsonObject,
        legacy: Boolean,
        now: Instant,
    ): AgentActivityProjection {
        val rawName = payload.firstString("name", "tool_name")?.trim().orEmpty()
        val name = safeToolName(
            rawName.ifBlank { "Tool activity" },
            payload.toolNameIsUnsafe(),
        )
        val callId = payload.activityCallId()
        val output = payload.detailFrom(
            keys = listOf("result_text", "summary", "output", "result", "error", "failure_reason"),
            limit = TOOL_ACTIVITY_DETAIL_LIMIT,
        )
        val input = payload.detailFrom(
            keys = listOf("args_text", "context", "args"),
            limit = TOOL_ACTIVITY_DETAIL_LIMIT,
        )
        val failed = payload.booleanLike("is_error", "failed") ||
            payload.firstString("status")?.lowercase() in setOf("error", "failed", "failure") ||
            payload.firstString("error", "failure_reason")?.isNotBlank() == true
        val phase = if (failed) ToolPhase.Failed else ToolPhase.Completed
        val items = projection.items.toMutableList()

        // A resume snapshot may already contain the terminal row for this
        // completion. Old gateways commonly replay the same tool.complete
        // without an event ID, so match the terminal row by its safe identity
        // and displayed fields before creating another occurrence.
        val terminalReplay = terminalToolReplayIndex(
            items = items,
            callId = callId,
            rawName = rawName,
            name = name,
            input = input,
            output = output,
            phase = phase,
        )
        if (terminalReplay >= 0) return projection

        val candidates = when {
            callId != null -> items.withIndex().filter { (_, item) ->
                item is ToolActivity && item.callId == callId && item.phase.isInFlight()
            }
            rawName.isNotBlank() -> items.withIndex().filter { (_, item) ->
                item is ToolActivity && item.name == name && item.phase.isInFlight()
            }
            else -> emptyList()
        }
        val ambiguous = candidates.size > 1
        val matchIndex = candidates.singleOrNull()?.index ?: -1
        val effectiveLegacy = legacy || callId == null || ambiguous
        if (matchIndex >= 0) {
            val existing = items[matchIndex] as ToolActivity
            items[matchIndex] = existing.copy(
                phase = phase,
                input = input ?: existing.input,
                output = output ?: existing.output,
                finishedAt = now,
                correlation = if (callId != null && !legacy) {
                    CorrelationQuality.ExactId
                } else {
                    CorrelationQuality.LegacyName
                },
            )
        } else {
            // No matching in-flight occurrence is a new, occurrence-qualified
            // card. In particular, a reused/terminal Hermes ID is not silently
            // treated as a replay; explicit event IDs are deduplicated above.
            items += ToolActivity(
                uiKey = nextActivityKey(projection, items, callId, name),
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
            source = if (effectiveLegacy) ActivitySource.Legacy else ActivitySource.Live,
            capability = capabilityWithTool(projection.capability, effectiveLegacy),
            presentation = if (items.any(ActivityItem::isInFlight)) {
                ActivityPresentationState.Running
            } else {
                ActivityPresentationState.Available
            },
            ambiguousCorrelationCount = if (ambiguous) {
                (projection.ambiguousCorrelationCount + 1).coerceAtMost(1_000)
            } else {
                projection.ambiguousCorrelationCount
            },
        )
    }

    private fun terminalToolReplayIndex(
        items: List<ActivityItem>,
        callId: String?,
        rawName: String,
        name: String,
        input: DisplayedDetail?,
        output: DisplayedDetail?,
        phase: ToolPhase,
    ): Int {
        val candidates = items.withIndex().filter { (_, item) ->
            item is ToolActivity &&
                item.phase.isTerminal() &&
                (callId == null || item.callId == callId) &&
                (rawName.isBlank() || item.name == name)
        }
        val matches = candidates.filter { (_, item) ->
            val tool = item as ToolActivity
            tool.phase == phase &&
                (input == null || tool.input?.text == input.text) &&
                (output == null || tool.output?.text == output.text)
        }
        return matches.singleOrNull()?.index ?: -1
    }

    private fun applyReasoningSummary(
        projection: AgentActivityProjection,
        payload: JsonObject,
    ): AgentActivityProjection {
        val detail = payload.reasoningDetailFrom(listOf("text"), REASONING_ACTIVITY_DETAIL_LIMIT)
            ?: return projection.copy(serverReasoningAllowed = true)
        val label = safeServerLabel(payload.firstString("label")) ?: "Server-provided summary"
        val item = ServerReasoningActivity(
            uiKey = nextReasoningKey(projection, projection.items),
            source = ReasoningSource.ServerSummary,
            phase = ReasoningPhase.Complete,
            text = detail,
            serverLabel = label,
        )
        val items = projection.items.toMutableList()
        val summaryIndex = items.indexOfLast {
            it is ServerReasoningActivity && it.source == ReasoningSource.ServerSummary
        }
        val streamingIndex = items.indexOfLast {
            it is ServerReasoningActivity && it.source == ReasoningSource.ServerFull
        }
        val duplicateStreaming = streamingIndex >= 0 &&
            items[streamingIndex] is ServerReasoningActivity &&
            reasoningTextMatches(
                (items[streamingIndex] as ServerReasoningActivity).text.text,
                detail.text,
            )
        when {
            duplicateStreaming -> {
                val existing = items[streamingIndex] as ServerReasoningActivity
                items[streamingIndex] = item.copy(uiKey = existing.uiKey)
            }
            summaryIndex >= 0 -> items[summaryIndex] = item.copy(uiKey = items[summaryIndex].uiKey)
            else -> items += item
        }
        return projection.copy(
            items = items.takeLast(MAX_ACTIVITY_ITEMS),
            source = ActivitySource.Live,
            capability = ActivityCapabilityState.ToolAndServerReasoning,
            serverReasoningAllowed = true,
            presentation = ActivityPresentationState.Available,
        )
    }

    private fun applyReasoningDelta(
        projection: AgentActivityProjection,
        payload: JsonObject,
    ): AgentActivityProjection {
        val detail = payload.reasoningDetailFrom(listOf("text"), REASONING_ACTIVITY_DETAIL_LIMIT)
            ?: return projection.copy(serverReasoningAllowed = true)
        val items = projection.items.toMutableList()
        val streamingIndex = items.indexOfLast {
            it is ServerReasoningActivity &&
                it.source == ReasoningSource.ServerFull &&
                it.phase == ReasoningPhase.Streaming
        }
        val index = if (streamingIndex >= 0) streamingIndex else items.indexOfLast {
            it is ServerReasoningActivity && it.phase != ReasoningPhase.Unavailable
        }
        if (index >= 0) {
            val existing = items[index] as ServerReasoningActivity
            if (reasoningDeltaIsReplay(existing.text.text, detail.text)) return projection
            val combined = if (detail.text.startsWith(existing.text.text)) {
                detail
            } else {
                sanitizeActivityDetail(
                    buildString(existing.text.text.length + detail.text.length) {
                        append(existing.text.text)
                        append(detail.text)
                    },
                    REASONING_ACTIVITY_DETAIL_LIMIT,
                )
            }
            items[index] = existing.copy(
                source = ReasoningSource.ServerFull,
                phase = ReasoningPhase.Streaming,
                text = combined,
                serverLabel = existing.serverLabel ?: "Server-provided reasoning",
            )
        } else {
            items += ServerReasoningActivity(
                uiKey = nextReasoningKey(projection, items),
                source = ReasoningSource.ServerFull,
                phase = ReasoningPhase.Streaming,
                text = detail,
                serverLabel = "Server-provided reasoning",
            )
        }
        return projection.copy(
            items = items.takeLast(MAX_ACTIVITY_ITEMS),
            source = ActivitySource.Live,
            capability = ActivityCapabilityState.ToolAndServerReasoning,
            serverReasoningAllowed = true,
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

    private fun applySessionInfo(
        projection: AgentActivityProjection,
        payload: JsonObject,
    ): AgentActivityProjection {
        val serverAllowed = payload.reasoningDisplayCapability() ?: return projection
        return AgentActivityReducer.applyServerReasoningCapability(projection, serverAllowed)
    }

    private fun malformed(projection: AgentActivityProjection): AgentActivityProjection =
        projection.copy(malformedEventCount = (projection.malformedEventCount + 1).coerceAtMost(1_000))

    private fun unavailableAfterMalformed(projection: AgentActivityProjection): AgentActivityProjection =
        markUnavailable(malformed(projection))

    private fun activityReplayKey(event: GatewayEvent): String? {
        val explicit = (
            event.eventId?.takeIf(String::isNotBlank)
                ?: event.payload.firstString("event_id", "eventId", "event_seq", "seq")
            )?.trim()?.takeIf(String::isNotBlank)
        return explicit?.let { "${event.type}:$it" }
    }

    private fun rememberReplayKey(existing: Set<String>, key: String): Set<String> =
        (existing.asSequence() + key)
            .toList()
            .distinct()
            .takeLast(MAX_SEEN_ACTIVITY_EVENT_KEYS)
            .toSet()

    private fun sameBinding(projection: AgentActivityProjection, binding: ActivityBinding): Boolean =
        normalizeActivityOrigin(binding.originKey) == projection.originKey &&
            binding.profile.trim().ifBlank { "default" } == projection.profile &&
            binding.storedSessionId.trim() == projection.storedSessionId

    private fun normalizeActivityKeys(
        items: List<ActivityItem>,
        storedSessionId: String,
    ): List<ActivityItem> {
        val occurrences = mutableMapOf<String, Int>()
        return items.map { item ->
            val identity = activityIdentity(item)
            val occurrence = occurrences.getOrDefault(identity, 0) + 1
            occurrences[identity] = occurrence
            item.copyWithUiKey(stableActivityKey(storedSessionId, identity, occurrence))
        }
    }

    private fun ActivityItem.copyWithUiKey(key: String): ActivityItem = when (this) {
        is ToolActivity -> copy(uiKey = key)
        is ServerReasoningActivity -> copy(uiKey = key)
    }

    private fun activityIdentity(item: ActivityItem): String = when (item) {
        is ToolActivity -> item.callId?.takeIf(String::isNotBlank)?.let { "tool-id:${safeKeyPart(it)}" }
            ?: "tool-legacy:${safeKeyPart(item.name.lowercase())}"
        is ServerReasoningActivity -> "reasoning:server"
    }

    private fun stableActivityKey(
        storedSessionId: String,
        identity: String,
        occurrence: Int,
    ): String = "activity:${storedSessionId.ifBlank { "session" }}:$identity:occurrence:$occurrence"

    private fun safeKeyPart(value: String): String = value.trim()
        .replace(Regex("[^A-Za-z0-9_.:-]"), "_")
        .take(96)
        .ifBlank { "unknown" }

    private fun nextActivityKey(
        projection: AgentActivityProjection,
        items: List<ActivityItem>,
        callId: String?,
        name: String,
    ): String {
        val identity = if (callId.isNullOrBlank()) {
            "tool-legacy:${safeKeyPart(name.lowercase())}"
        } else {
            "tool-id:${safeKeyPart(callId)}"
        }
        val occurrence = items.count { activityIdentity(it) == identity } + 1
        var candidate = stableActivityKey(projection.storedSessionId, identity, occurrence)
        val keys = items.asSequence().map(ActivityItem::uiKey).toSet()
        var nextOccurrence = occurrence
        while (candidate in keys) {
            nextOccurrence += 1
            candidate = stableActivityKey(projection.storedSessionId, identity, nextOccurrence)
        }
        return candidate
    }

    private fun nextReasoningKey(
        projection: AgentActivityProjection,
        items: List<ActivityItem>,
    ): String {
        val identity = "reasoning:server"
        val occurrence = items.count { it is ServerReasoningActivity } + 1
        var candidate = stableActivityKey(projection.storedSessionId, identity, occurrence)
        val keys = items.asSequence().map(ActivityItem::uiKey).toSet()
        var nextOccurrence = occurrence
        while (candidate in keys) {
            nextOccurrence += 1
            candidate = stableActivityKey(projection.storedSessionId, identity, nextOccurrence)
        }
        return candidate
    }

    private fun capabilityForItems(items: List<ActivityItem>): ActivityCapabilityState {
        val hasReasoning = items.any {
            it is ServerReasoningActivity && it.phase != ReasoningPhase.Unavailable
        }
        val tools = items.filterIsInstance<ToolActivity>()
        if (hasReasoning) return ActivityCapabilityState.ToolAndServerReasoning
        if (tools.isEmpty()) return ActivityCapabilityState.Unknown
        return if (tools.any { it.correlation != CorrelationQuality.ExactId }) {
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
        legacy || current == ActivityCapabilityState.LegacyToolOnly ->
            ActivityCapabilityState.LegacyToolOnly
        else -> ActivityCapabilityState.ToolOnly
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
                val rawName = row.firstString("name", "tool_name").orEmpty()
                val callId = row.activityCallId()
                val status = row.firstString("status")?.lowercase()
                val failed = row.booleanLike("is_error", "failed") ||
                    status in setOf("error", "failed", "failure") ||
                    row.firstString("failure_reason")?.isNotBlank() == true
                val running = !failed && (
                    row.booleanLike("pending", "running", "in_progress", "streaming") ||
                        status in setOf("started", "running", "in_progress", "streaming", "queued")
                    )
                ToolActivity(
                    uiKey = "",
                    callId = callId,
                    name = safeToolName(
                        rawName.ifBlank { "Tool activity" },
                        row.toolNameIsUnsafe(),
                    ),
                    phase = when {
                        failed -> ToolPhase.Failed
                        running -> ToolPhase.Running
                        else -> ToolPhase.Completed
                    },
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
                    progress = row.detailFrom(
                        listOf("progress", "preview", "delta"),
                        TOOL_ACTIVITY_DETAIL_LIMIT,
                    ),
                )
            }
            "assistant" -> {
                // Hermes' `reasoning_content` is provider-facing thinking text,
                // not a user-visible activity surface. Only the explicit
                // server-authored `reasoning` summary is eligible here.
                val detail = row.reasoningDetailFrom(listOf("reasoning"), REASONING_ACTIVITY_DETAIL_LIMIT)
                    ?: return@mapNotNull null
                // A persisted assistant `reasoning` field is not proof that the
                // text was user-visible. Require either an explicit disclosure
                // capability or the source-verified verbose marker; omission is
                // deliberately fail-closed to protect provider/model reasoning.
                val explicitCapability = row.reasoningDisplayCapability()
                if (
                    explicitCapability == false ||
                    (explicitCapability != true && row.booleanValue("verbose") != true)
                ) return@mapNotNull null
                ServerReasoningActivity(
                    uiKey = "",
                    source = ReasoningSource.ServerSummary,
                    phase = ReasoningPhase.Complete,
                    text = detail,
                    serverLabel = safeServerLabel(row.firstString("label")) ?: "Server-provided summary",
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

private fun JsonObject.booleanValue(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.reasoningDisplayCapability(): Boolean? {
    val direct = sequenceOf(
        "show_reasoning",
        "reasoning_visible",
        "reasoning_enabled",
        "server_reasoning",
    ).mapNotNull(::booleanValue).firstOrNull()
    if (direct != null) return direct
    val display = this["display"] as? JsonObject
    display?.booleanValue("show_reasoning")?.let { return it }
    val capabilities = this["capabilities"] as? JsonObject
    capabilities?.booleanValue("reasoning")?.let { return it }
    return null
}

private fun JsonObject.isFailure(): Boolean =
    booleanLike("is_error", "failed") ||
        firstString("status")?.lowercase() in setOf("error", "failed", "failure") ||
        firstString("error", "failure_reason")?.isNotBlank() == true

private fun reasoningTextMatches(existing: String, incoming: String): Boolean =
    existing == incoming || existing.startsWith(incoming) || incoming.startsWith(existing)

private fun reasoningDeltaIsReplay(existing: String, incoming: String): Boolean =
    existing == incoming || existing.startsWith(incoming) || existing.endsWith(incoming)

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
        return sanitizeActivityDetail(text, limit)
    }
    return null
}

private fun JsonObject.reasoningDetailFrom(keys: List<String>, limit: Int): DisplayedDetail? {
    for (key in keys) {
        val value = this[key] ?: continue
        val text = value.displayText() ?: continue
        if (text.isBlank() || hiddenReasoningTagPattern.containsMatchIn(text)) continue
        return sanitizeActivityDetail(text, limit)
    }
    return null
}

private fun JsonElement.displayText(): String? = when (this) {
    JsonNull -> null
    is JsonPrimitive -> contentOrNull
    else -> toString()
}

internal fun safeToolName(raw: String, unsafe: Boolean = false): String {
    val candidate = raw.trim()
    if (candidate.isBlank() || unsafe) return "Tool activity"
    if (
        candidate.length > 80 ||
        !candidate.matches(Regex("[A-Za-z0-9_.:-]+")) ||
        sanitizeActivityText(candidate, 80) != candidate
    ) {
        return "Tool activity"
    }
    return candidate.replace('_', ' ')
}

internal fun JsonObject.toolNameIsUnsafe(): Boolean {
    fun marker(value: JsonObject): Boolean {
        val unsafeKeys = listOf(
            "unsafe_name",
            "unsafe_tool_name",
            "name_unsafe",
            "sensitive_name",
            "name_sensitive",
            "tool_name_sensitive",
            "private_name",
        )
        val safeKeys = listOf("name_safe", "tool_name_safe", "safe_tool_name", "name_is_safe")
        if (unsafeKeys.any { key -> booleanMarker(value[key]) == true }) return true
        if (safeKeys.any { key -> booleanMarker(value[key]) == false }) return true
        if (booleanMarker(value["sensitive"]) == true || booleanMarker(value["private"]) == true) {
            return true
        }
        return false
    }

    if (marker(this)) return true
    return listOf("tool", "tool_call", "metadata", "tool_metadata")
        .mapNotNull { this[it] as? JsonObject }
        .any(::marker)
}

private fun booleanMarker(element: JsonElement?): Boolean? {
    val primitive = element as? JsonPrimitive ?: return null
    return primitive.booleanOrNull ?: primitive.contentOrNull?.trim()?.lowercase()?.let {
        when (it) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> null
        }
    }
}

private fun safeServerLabel(raw: String?): String? {
    val candidate = raw?.trim().orEmpty()
    if (candidate.isBlank() || candidate.length > 80) return null
    return if (candidate.matches(Regex("[A-Za-z0-9 ._:-]+"))) candidate else null
}

private fun ToolPhase.isInFlight(): Boolean = this == ToolPhase.Started || this == ToolPhase.Running
private fun ToolPhase.isTerminal(): Boolean = !isInFlight()
private fun ActivityItem.isInFlight(): Boolean =
    this is ToolActivity && phase.isInFlight() ||
        this is ServerReasoningActivity && phase == ReasoningPhase.Streaming
