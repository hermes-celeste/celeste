package dev.hazydreams.hermesceleste.network

import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentActivityTest {
    private val now = Instant.parse("2026-08-15T12:00:00Z")

    @Test
    fun sanitizerRedactsCredentialsAndBoundsUnicodeWithoutRestoration() {
        val raw = "Bearer synthetic-bearer-token api_key=synthetic-api-key " + "😀".repeat(30)

        val detail = sanitizeActivityDetail(raw, maxCodePoints = 24)

        assertTrue(detail.wasRedacted)
        assertTrue(detail.wasTruncated)
        assertFalse(detail.canRestore)
        assertFalse(detail.text.contains("synthetic-bearer-token"))
        assertFalse(detail.text.contains("synthetic-api-key"))
        assertEquals(24, detail.text.codePointCount(0, detail.text.length))
    }

    @Test
    fun exactToolIdPairsCompletionAndKeepsToolSeparateFromReasoning() {
        var projection = initialActivityProjection(
            originKey = "https://hermes.test/",
            profile = "default",
            storedSessionId = "stored-42",
            runtimeSessionId = "runtime-7",
        )
        projection = reduceActivityEvent(
            projection,
            event(
                type = "tool.start",
                sessionId = "runtime-7",
                payload = buildJsonObject {
                    put("name", "terminal")
                    put("tool_call_id", "call-1")
                    put("args", "<tool-input>")
                },
            ),
            now = now,
        )
        projection = reduceActivityEvent(
            projection,
            event(
                type = "reasoning.available",
                sessionId = "runtime-7",
                payload = buildJsonObject { put("text", "<server-summary>") },
            ),
            now = now,
        )
        projection = reduceActivityEvent(
            projection,
            event(
                type = "tool.complete",
                sessionId = "runtime-7",
                payload = buildJsonObject {
                    put("name", "terminal")
                    put("tool_call_id", "call-1")
                    put("output", "<tool-output>")
                },
            ),
            now = now,
        )

        val tool = projection.items.filterIsInstance<ToolActivity>().single()
        val reasoning = projection.items.filterIsInstance<ServerReasoningActivity>().single()
        assertEquals("call-1", tool.callId)
        assertEquals(ToolPhase.Completed, tool.phase)
        assertEquals(CorrelationQuality.ExactId, tool.correlation)
        assertEquals(ReasoningSource.ServerSummary, reasoning.source)
        assertEquals("<server-summary>", reasoning.text.text)
        assertEquals(ActivityCapabilityState.ToolAndServerReasoning, projection.capability)
    }

    @Test
    fun snapshotDecoderKeepsToolDetailsAndServerReasoningAsDifferentItems() {
        val items = decodeGatewayActivity(
            Json.parseToJsonElement(
                """
                [
                  {"role":"tool","name":"terminal","tool_call_id":"call-1","args":"<tool-input>","output":"<tool-output>"},
                  {"role":"assistant","reasoning":"<server-summary>","content":"<assistant-content>"}
                ]
                """.trimIndent(),
            ).jsonArray,
        )

        assertEquals(
            listOf("tool", "reasoning"),
            items.map { item ->
                when (item) {
                    is ToolActivity -> "tool"
                    is ServerReasoningActivity -> "reasoning"
                }
            },
        )
        assertEquals("<tool-input>", items.filterIsInstance<ToolActivity>().single().input?.text)
        assertEquals("<tool-output>", items.filterIsInstance<ToolActivity>().single().output?.text)
        assertEquals("<server-summary>", items.filterIsInstance<ServerReasoningActivity>().single().text.text)
    }

    @Test
    fun overlappingLegacyCallsDoNotClaimACompletionPairing() {
        var projection = initialActivityProjection(
            originKey = "https://hermes.test",
            profile = "default",
            storedSessionId = "stored-42",
            runtimeSessionId = "runtime-7",
        )
        repeat(2) {
            projection = reduceActivityEvent(
                projection,
                event(
                    type = "tool_call",
                    sessionId = "runtime-7",
                    payload = buildJsonObject { put("name", "terminal") },
                ),
                now = now,
            )
        }
        projection = reduceActivityEvent(
            projection,
            event(
                type = "tool_result",
                sessionId = "runtime-7",
                payload = buildJsonObject {
                    put("name", "terminal")
                    put("output", "<ambiguous-output>")
                },
            ),
            now = now,
        )

        assertEquals(1, projection.ambiguousCorrelationCount)
        assertEquals(3, projection.items.size)
        assertEquals(
            listOf(ToolPhase.Started, ToolPhase.Started, ToolPhase.Completed),
            projection.items.filterIsInstance<ToolActivity>().map(ToolActivity::phase),
        )
        assertEquals(
            CorrelationQuality.Uncorrelated,
            projection.items.filterIsInstance<ToolActivity>().last().correlation,
        )
    }

    @Test
    fun wrongRuntimeEventsAndWrongSnapshotBindingsAreIgnored() {
        val projection = initialActivityProjection(
            originKey = "https://hermes.test",
            profile = "default",
            storedSessionId = "stored-42",
            runtimeSessionId = "runtime-7",
        )
        val event = event(
            type = "tool.start",
            sessionId = "other-runtime",
            payload = buildJsonObject { put("name", "terminal") },
        )

        assertEquals(projection, reduceActivityEvent(projection, event, now = now))
        val snapshot = AgentActivityReducer.applySnapshot(
            projection = projection,
            items = emptyList(),
            binding = ActivityBinding(
                originKey = "https://other.example",
                profile = "default",
                storedSessionId = "stored-42",
                runtimeSessionId = "other-runtime",
            ),
            running = false,
            now = now,
        )
        assertEquals(1, snapshot.malformedEventCount)
        assertEquals(projection.items, snapshot.items)
    }

    @Test
    fun snapshotItemsReceiveDeterministicNamespacedKeys() {
        val projection = initialActivityProjection(
            originKey = "https://hermes.test",
            profile = "work",
            storedSessionId = "stored-42",
            runtimeSessionId = "runtime-7",
        )
        val snapshot = AgentActivityReducer.applySnapshot(
            projection = projection,
            items = listOf(
                ToolActivity(
                    uiKey = "",
                    callId = null,
                    name = "terminal",
                    phase = ToolPhase.Completed,
                    input = null,
                    output = null,
                    startedAt = null,
                    finishedAt = null,
                    correlation = CorrelationQuality.LegacyName,
                ),
                ServerReasoningActivity(
                    uiKey = "",
                    source = ReasoningSource.ServerSummary,
                    phase = ReasoningPhase.Complete,
                    text = sanitizeActivityDetail("<server-summary>", REASONING_ACTIVITY_DETAIL_LIMIT),
                    serverLabel = "Server-provided summary",
                ),
            ),
            binding = ActivityBinding(
                originKey = "https://hermes.test/",
                profile = "work",
                storedSessionId = "stored-42",
                runtimeSessionId = "runtime-8",
            ),
            running = false,
            now = now,
        )

        assertEquals(
            listOf(
                "activity:stored-42:snapshot:tool:0",
                "activity:stored-42:snapshot:reasoning:1",
            ),
            snapshot.items.map(ActivityItem::uiKey),
        )
    }

    @Test
    fun disabledDisclosureRemovesReasoningBodyButRetainsCapabilityFact() {
        var projection = initialActivityProjection(
            originKey = "https://hermes.test",
            profile = "default",
            storedSessionId = "stored-42",
            runtimeSessionId = "runtime-7",
        )
        projection = reduceActivityEvent(
            projection,
            event(
                type = "reasoning.available",
                sessionId = "runtime-7",
                payload = buildJsonObject { put("text", "<server-summary>") },
            ),
            now = now,
        )

        val hidden = AgentActivityReducer.withoutServerReasoning(projection)

        assertTrue(hidden.items.isEmpty())
        assertEquals(ActivityCapabilityState.ToolAndServerReasoning, hidden.capability)
    }

    private fun event(
        type: String,
        sessionId: String,
        payload: kotlinx.serialization.json.JsonObject,
    ): GatewayEvent = GatewayEvent(type = type, sessionId = sessionId, payload = payload)
}
