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
    fun statusCapabilityDecodingIgnoresUndocumentedFields() {
        listOf(
            buildJsonObject { put("activity_capability", "tool_only") },
            buildJsonObject { put("activity_support", "available") },
            buildJsonObject { put("activity_supported", false) },
            buildJsonObject { put("activity", buildJsonObject { put("server_reasoning", true) }) },
            buildJsonObject {
                put("capabilities", buildJsonObject { put("activity", "legacy") })
            },
        ).forEach { status ->
            assertEquals(ActivityCapabilityState.Unknown, decodeActivityCapability(status))
        }
    }

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
    fun sanitizerRedactsNestedAndQuotedJsonCredentials() {
        val structured = sanitizeActivityDetail(
            """{"credentials":{"password":"synthetic-json-password","api_key":"synthetic-json-api-key"},"safe":"<tool-output>"}""",
            maxCodePoints = 1_000,
        )
        val quotedKeyText = sanitizeActivityDetail(
            """payload {"password":"synthetic-quoted-password"} trailing""",
            maxCodePoints = 1_000,
        )

        listOf(structured, quotedKeyText).forEach { detail ->
            assertTrue(detail.wasRedacted)
            assertTrue(detail.text.contains("[redacted]"))
        }
        listOf(
            "synthetic-json-password",
            "synthetic-json-api-key",
            "synthetic-quoted-password",
        ).forEach { secret ->
            assertFalse(structured.text.contains(secret))
            assertFalse(quotedKeyText.text.contains(secret))
        }
    }

    @Test
    fun sanitizerRecursivelyRedactsTransportCredentialJsonKeys() {
        val detail = sanitizeActivityDetail(
            """
            {"outer":[
              {"Authorization":"synthetic-authorization"},
              {"proxy-authorization":"synthetic-proxy-authorization"},
              {"Cookie":"synthetic-cookie"},
              {"set-cookie":["synthetic-set-cookie"]}
            ]}
            """.trimIndent(),
            maxCodePoints = 1_000,
        )

        val embedded = sanitizeActivityDetail(
            "wrapper {\"headers\":{\"authorization\":\"synthetic-embedded-authorization\"}}",
            maxCodePoints = 1_000,
        )

        assertTrue(detail.wasRedacted)
        assertTrue(embedded.wasRedacted)
        listOf(
            detail,
            embedded,
        ).forEach { sanitized ->
            listOf(
                "synthetic-authorization",
                "synthetic-proxy-authorization",
                "synthetic-cookie",
                "synthetic-set-cookie",
                "synthetic-embedded-authorization",
            ).forEach { secret -> assertFalse(sanitized.text.contains(secret)) }
        }
        assertTrue(detail.text.contains("[redacted]"))
        assertTrue(embedded.text.contains("[redacted]"))
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
                payload = buildJsonObject {
                    put("text", "<server-summary>")
                    put("verbose", true)
                },
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
    fun reasoningAvailableWithoutVerboseIsSuppressedFailClosed() {
        val projection = initialActivityProjection(
            originKey = "https://hermes.test",
            profile = "default",
            storedSessionId = "stored-42",
            runtimeSessionId = "runtime-7",
        )
        val suppressed = reduceActivityEvent(
            projection,
            event(
                type = "reasoning.available",
                sessionId = "runtime-7",
                payload = buildJsonObject { put("text", "<undisclosed-summary>") },
            ),
            now = now,
        )

        assertEquals(projection, suppressed)
        assertTrue(suppressed.items.isEmpty())
        assertEquals(ActivityCapabilityState.Unknown, suppressed.capability)
    }

    @Test
    fun snapshotDecoderKeepsToolDetailsAndServerReasoningAsDifferentItems() {
        val items = decodeGatewayActivity(
            Json.parseToJsonElement(
                """
                [
                  {"role":"tool","name":"terminal","tool_call_id":"call-1","args":"<tool-input>","output":"<tool-output>"},
                  {"role":"assistant","reasoning":"<server-summary>","verbose":true,"content":"<assistant-content>"}
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
                "activity:stored-42:tool-legacy:terminal:occurrence:1",
                "activity:stored-42:reasoning:server:occurrence:1",
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
                payload = buildJsonObject {
                    put("text", "<server-summary>")
                    put("verbose", true)
                },
            ),
            now = now,
        )

        val hidden = AgentActivityReducer.withoutServerReasoning(projection)

        assertTrue(hidden.items.isEmpty())
        assertEquals(ActivityCapabilityState.ToolAndServerReasoning, hidden.capability)
    }

    @Test
    fun officialToolProgressUpdatesTheCorrelatedCardAndSurvivesCompletion() {
        var projection = initialActivityProjection(
            originKey = "https://hermes.test",
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
                    put("tool_id", "call-progress")
                },
            ),
            now = now,
        )
        projection = reduceActivityEvent(
            projection,
            event(
                type = "tool.progress",
                sessionId = "runtime-7",
                payload = buildJsonObject {
                    put("tool_id", "call-progress")
                    put("progress", "<tool-progress>")
                },
            ),
            now = now,
        )

        val running = projection.items.filterIsInstance<ToolActivity>().single()
        assertEquals(ToolPhase.Running, running.phase)
        assertEquals("<tool-progress>", running.progress?.text)
        assertEquals("call-progress", running.callId)

        projection = reduceActivityEvent(
            projection,
            event(
                type = "tool.complete",
                sessionId = "runtime-7",
                payload = buildJsonObject {
                    put("tool_id", "call-progress")
                    put("output", "<tool-output>")
                },
            ),
            now = now,
        )
        val completed = projection.items.filterIsInstance<ToolActivity>().single()
        assertEquals(ToolPhase.Completed, completed.phase)
        assertEquals("<tool-progress>", completed.progress?.text)
        assertEquals("<tool-output>", completed.output?.text)
    }

    @Test
    fun missingIdOfficialEventsUseLegacyCorrelationInsteadOfExactId() {
        var projection = initialActivityProjection(
            originKey = "https://hermes.test",
            profile = "default",
            storedSessionId = "stored-42",
            runtimeSessionId = "runtime-7",
        )
        projection = reduceActivityEvent(
            projection,
            event(
                type = "tool.start",
                sessionId = "runtime-7",
                payload = buildJsonObject { put("name", "terminal") },
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
                    put("output", "<legacy-output>")
                },
            ),
            now = now,
        )

        val tool = projection.items.filterIsInstance<ToolActivity>().single()
        assertEquals(ToolPhase.Completed, tool.phase)
        assertEquals(CorrelationQuality.LegacyName, tool.correlation)
        assertEquals(ActivitySource.Legacy, projection.source)
        assertEquals(ActivityCapabilityState.LegacyToolOnly, projection.capability)
    }

    @Test
    fun idlessToolStartReplayDoesNotCreateAnotherOccurrence() {
        val projection = initialActivityProjection(
            originKey = "https://hermes.test",
            profile = "default",
            storedSessionId = "stored-42",
            runtimeSessionId = "runtime-7",
        )
        val start = event(
            type = "tool.start",
            sessionId = "runtime-7",
            payload = buildJsonObject {
                put("name", "terminal")
                put("args", "<tool-input>")
            },
        )

        val first = reduceActivityEvent(projection, start, now = now)
        val replay = reduceActivityEvent(first, start, now = now.plusSeconds(1))

        assertEquals(first, replay)
        assertEquals(1, replay.items.filterIsInstance<ToolActivity>().size)
        assertEquals(
            CorrelationQuality.LegacyName,
            (replay.items.single() as ToolActivity).correlation,
        )
    }

    @Test
    fun explicitEventReplayIsIgnoredButAReusedToolIdGetsAnotherOccurrence() {
        var projection = initialActivityProjection(
            originKey = "https://hermes.test",
            profile = "default",
            storedSessionId = "stored-42",
            runtimeSessionId = "runtime-7",
        )
        val start = event(
            type = "tool.start",
            sessionId = "runtime-7",
            payload = buildJsonObject {
                put("name", "terminal")
                put("tool_call_id", "reused-call")
            },
        ).copy(eventId = "event-start-1")
        projection = reduceActivityEvent(projection, start, now = now)
        projection = reduceActivityEvent(projection, start, now = now)
        val complete = event(
            type = "tool.complete",
            sessionId = "runtime-7",
            payload = buildJsonObject {
                put("name", "terminal")
                put("tool_call_id", "reused-call")
            },
        ).copy(eventId = "event-complete-1")
        projection = reduceActivityEvent(projection, complete, now = now)
        projection = reduceActivityEvent(projection, complete, now = now)
        projection = reduceActivityEvent(
            projection,
            start.copy(eventId = "event-start-2"),
            now = now,
        )

        val tools = projection.items.filterIsInstance<ToolActivity>()
        assertEquals(2, tools.size)
        assertEquals(listOf(ToolPhase.Completed, ToolPhase.Started), tools.map(ToolActivity::phase))
        assertEquals(2, tools.map(ActivityItem::uiKey).toSet().size)
    }

    @Test
    fun duplicateTerminalRowsWithoutEventIdDoNotAmplifyReplay() {
        val projection = initialActivityProjection(
            originKey = "https://hermes.test",
            profile = "default",
            storedSessionId = "stored-42",
            runtimeSessionId = "runtime-7",
        )
        val terminalRow = ToolActivity(
            uiKey = "",
            callId = null,
            name = "terminal",
            phase = ToolPhase.Completed,
            input = null,
            output = sanitizeActivityDetail("<tool-output>", TOOL_ACTIVITY_DETAIL_LIMIT),
            startedAt = null,
            finishedAt = now,
            correlation = CorrelationQuality.LegacyName,
        )
        val terminalSnapshot = AgentActivityReducer.applySnapshot(
            projection = projection,
            items = listOf(terminalRow, terminalRow),
            binding = ActivityBinding(
                originKey = "https://hermes.test/",
                profile = "default",
                storedSessionId = "stored-42",
                runtimeSessionId = "runtime-7",
            ),
            running = false,
            now = now,
        )

        val replay = reduceActivityEvent(
            terminalSnapshot,
            event(
                type = "tool.complete",
                sessionId = "runtime-7",
                payload = buildJsonObject {
                    put("name", "terminal")
                    put("output", "<tool-output>")
                },
            ),
            now = now,
        )

        assertEquals(terminalSnapshot, replay)
        assertEquals(2, replay.items.filterIsInstance<ToolActivity>().size)
    }

    @Test
    fun malformedActivityEventDowngradesOnlyActivityPresentation() {
        val projection = initialActivityProjection(
            originKey = "https://hermes.test",
            profile = "default",
            storedSessionId = "stored-42",
            runtimeSessionId = "runtime-7",
        )
        val updated = reduceActivityEvent(
            projection,
            event(
                type = "tool.future_variant",
                sessionId = "runtime-7",
                payload = buildJsonObject {},
            ),
            now = now,
        )

        assertEquals(ActivityCapabilityState.Unsupported, updated.capability)
        assertEquals(ActivityPresentationState.Unavailable, updated.presentation)
        assertEquals(1, updated.malformedEventCount)
        assertEquals(projection.items, updated.items)
    }

    @Test
    fun incompleteToolCardsSettleAsInterruptedOrFailed() {
        var projection = initialActivityProjection(
            originKey = "https://hermes.test",
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
                    put("tool_call_id", "call-interrupted")
                },
            ),
            now = now,
        )
        projection = reduceActivityEvent(
            projection,
            event("message.complete", "runtime-7", buildJsonObject { put("status", "complete") }),
            now = now,
        )
        assertEquals(
            ToolPhase.Interrupted,
            projection.items.filterIsInstance<ToolActivity>().single().phase,
        )

        projection = reduceActivityEvent(
            projection,
            event(
                type = "tool.start",
                sessionId = "runtime-7",
                payload = buildJsonObject {
                    put("name", "terminal")
                    put("tool_call_id", "call-failed")
                },
            ),
            now = now,
        )
        projection = reduceActivityEvent(
            projection,
            event("message.error", "runtime-7", buildJsonObject {}),
            now = now,
        )
        assertEquals(
            ToolPhase.Failed,
            projection.items.filterIsInstance<ToolActivity>().last().phase,
        )
    }

    @Test
    fun snapshotReasoningWithoutDisclosureProofIsOmitted() {
        val items = decodeGatewayActivity(
            Json.parseToJsonElement(
                """
                [
                  {"role":"assistant","reasoning":"<provider-reasoning>"},
                  {"role":"assistant","reasoning":"<effort-only>","reasoning_effort":"high"},
                  {"role":"assistant","reasoning":"<explicitly-hidden>","show_reasoning":false,"verbose":true}
                ]
                """.trimIndent(),
            ).jsonArray,
        )

        assertTrue(items.isEmpty())
    }

    @Test
    fun reasoningRequiresTheExplicitStreamAndNeverUsesProviderThinkingContent() {
        var projection = initialActivityProjection(
            originKey = "https://hermes.test",
            profile = "default",
            storedSessionId = "stored-42",
            runtimeSessionId = "runtime-7",
        )
        projection = reduceActivityEvent(
            projection,
            event(
                type = "reasoning.delta",
                sessionId = "runtime-7",
                payload = buildJsonObject { put("text", "<unmarked-thinking>") },
            ),
            now = now,
        )
        assertTrue(projection.items.isEmpty())
        projection = reduceActivityEvent(
            projection,
            event(
                type = "reasoning.delta",
                sessionId = "runtime-7",
                payload = buildJsonObject {
                    put("text", "<server-stream>")
                    put("verbose", true)
                },
            ),
            now = now,
        )
        projection = reduceActivityEvent(
            projection,
            event(
                type = "reasoning.delta",
                sessionId = "runtime-7",
                payload = buildJsonObject {
                    put("text", "<server-stream>")
                    put("verbose", true)
                },
            ),
            now = now,
        )
        assertEquals(1, projection.items.filterIsInstance<ServerReasoningActivity>().size)
        assertEquals(
            "<server-stream>",
            projection.items.filterIsInstance<ServerReasoningActivity>().single().text.text,
        )

        val snapshotItems = decodeGatewayActivity(
            Json.parseToJsonElement(
                """
                [
                  {"role":"assistant","reasoning_content":"<private-thinking>"},
                  {"role":"assistant","reasoning":"<think>hidden</think>","verbose":true},
                  {"role":"assistant","reasoning":"<server-summary>","verbose":true}
                ]
                """.trimIndent(),
            ).jsonArray,
        )
        assertEquals(1, snapshotItems.size)
        assertEquals("<server-summary>", snapshotItems.single().let { (it as ServerReasoningActivity).text.text })
    }

    @Test
    fun unsafeToolNameMarkersFailClosedAcrossSnapshotsAndLegacyRows() {
        val elements = Json.parseToJsonElement(
            """
            [
              {"role":"tool","name":"secret_operation","metadata":{"sensitive":true},"output":"<tool-output>"}
            ]
            """.trimIndent(),
        ).jsonArray

        val activity = decodeGatewayActivity(elements).single() as ToolActivity
        val message = decodeGatewayMessages(elements).single()

        assertEquals("Tool activity", safeToolName("secret_operation", unsafe = true))
        assertEquals("Tool activity", activity.name)
        assertEquals("Tool activity", message.toolName)
        assertFalse(activity.name.contains("secret_operation"))
        assertFalse(message.toolName.orEmpty().contains("secret_operation"))
    }

    @Test
    fun sanitizerRedactsHeadersQueriesTokensAndPrivateKeyBlocks() {
        val bearer = "synthetic-" + "bearer-token"
        val cookie = "synthetic-" + "cookie-value"
        val sessionToken = "synthetic-" + "session-token"
        val queryKey = "synthetic-" + "query-key"
        val queryToken = "synthetic-" + "query-token"
        val privateBody = "synthetic-" + "private-key"
        val privateBegin = "-----" + "BEGIN PRIVATE KEY" + "-----"
        val privateEnd = "-----" + "END PRIVATE KEY" + "-----"
        val openAiToken = "sk-" + "synthetic-openai-token-1234"
        val githubToken = "ghp_" + "syntheticgithub1234"
        val jwtPayload = "syntheticjwtpayload0000000000"
        val raw = listOf(
            "Authorization: Bearer $bearer",
            "Cookie: session=$cookie",
            "x-hermes-session-token: $sessionToken",
            "https://hermes.test/?api_key=$queryKey&token=$queryToken",
            "$privateBegin\n$privateBody\n$privateEnd",
            openAiToken,
            githubToken,
            "eyJ$jwtPayload.syntheticjwtsignature0000000000.syntheticjwtfinal0000000000",
        ).joinToString("\n")

        val detail = sanitizeActivityDetail(raw, maxCodePoints = 4_000)

        assertTrue(detail.wasRedacted)
        assertTrue(detail.text.contains("[redacted]"))
        listOf(
            bearer,
            cookie,
            sessionToken,
            queryKey,
            queryToken,
            privateBody,
            openAiToken,
            githubToken,
            jwtPayload,
        ).forEach { secret -> assertFalse(detail.text.contains(secret)) }
    }

    @Test
    fun redactionCoversSessionTokensAndBoundedActivityKeepsLatestHundredItems() {
        val detail = sanitizeActivityDetail(
            "session_token=synthetic-session-token x-hermes-session-token:synthetic-header-token",
            500,
        )
        assertTrue(detail.wasRedacted)
        assertFalse(detail.text.contains("synthetic-session-token"))
        assertFalse(detail.text.contains("synthetic-header-token"))

        var projection = initialActivityProjection(
            originKey = "https://hermes.test",
            profile = "default",
            storedSessionId = "stored-42",
            runtimeSessionId = "runtime-7",
        )
        repeat(105) { index ->
            projection = reduceActivityEvent(
                projection,
                event(
                    type = "tool.start",
                    sessionId = "runtime-7",
                    payload = buildJsonObject {
                        put("name", "terminal")
                        put("tool_call_id", "call-$index")
                    },
                ),
                now = now,
            )
        }
        val tools = projection.items.filterIsInstance<ToolActivity>()
        assertEquals(100, tools.size)
        assertEquals("call-5", tools.first().callId)
        assertEquals("call-104", tools.last().callId)
    }

    private fun event(
        type: String,
        sessionId: String,
        payload: kotlinx.serialization.json.JsonObject,
    ): GatewayEvent = GatewayEvent(type = type, sessionId = sessionId, payload = payload)

}
