package dev.hazydreams.hermesceleste.ui.conversation

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertIsSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hazydreams.hermesceleste.network.ActivityCapabilityState
import dev.hazydreams.hermesceleste.network.ActivityItem
import dev.hazydreams.hermesceleste.network.ActivityPresentationState
import dev.hazydreams.hermesceleste.network.ActivitySource
import dev.hazydreams.hermesceleste.network.AgentActivityProjection
import dev.hazydreams.hermesceleste.network.CorrelationQuality
import dev.hazydreams.hermesceleste.network.DisplayedDetail
import dev.hazydreams.hermesceleste.network.ReasoningPhase
import dev.hazydreams.hermesceleste.network.ReasoningSource
import dev.hazydreams.hermesceleste.network.ServerReasoningActivity
import dev.hazydreams.hermesceleste.network.ToolActivity
import dev.hazydreams.hermesceleste.network.ToolPhase
import dev.hazydreams.hermesceleste.network.initialActivityProjection
import dev.hazydreams.hermesceleste.ui.HermesCelesteTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentActivityCardsSemanticsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun mountedActivityUsesButtonRolesLabelsAndVisibleExpandedState() {
        setActivityContent()

        val activity = composeRule.onNodeWithContentDescription("Agent activity, Available")
        activity.assert(hasRole(Role.Button))
        activity.assert(hasStateDescription("Collapsed"))
        activity.performClick()
        activity.assert(hasStateDescription("Expanded"))

        val tool = composeRule.onNodeWithContentDescription(
            "Tool terminal, Completed",
            useUnmergedTree = true,
        )
        tool.assert(hasRole(Role.Button))
        tool.assert(hasStateDescription("Collapsed"))
        tool.performClick()
        tool.assert(hasStateDescription("Expanded"))

        composeRule.onNodeWithContentDescription(
            "Server-provided reasoning, Complete",
            useUnmergedTree = true,
        ).assert(hasRole(Role.Button))
        composeRule.onNodeWithText("Tool", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Server-provided reasoning", useUnmergedTree = true).assertExists()
    }

    @Test
    fun mountedActivityMarksPhaseChangesAsPoliteLiveRegions() {
        setActivityContent()
        composeRule.onNodeWithContentDescription("Agent activity, Available").performClick()

        composeRule.onNodeWithContentDescription(
            "Tool, completed",
            useUnmergedTree = true,
        ).assert(hasLiveRegion(LiveRegionMode.Polite))
        composeRule.onNodeWithContentDescription(
            "Server-provided reasoning, complete",
            useUnmergedTree = true,
        ).assert(hasLiveRegion(LiveRegionMode.Polite))
    }

    @Test
    fun mountedActivityDetailIsSelectableAndContainsOnlySanitizedText() {
        setActivityContent()
        composeRule.onNodeWithContentDescription("Agent activity, Available").performClick()
        composeRule.onNodeWithContentDescription(
            "Tool terminal, Completed",
            useUnmergedTree = true,
        ).performClick()

        composeRule.onNodeWithText("[redacted]", useUnmergedTree = true).assertIsSelectable()
        composeRule.onNodeWithContentDescription(
            "Displayed output, selectable displayed detail",
            useUnmergedTree = true,
        ).assertIsSelectable()
        composeRule.onNodeWithText("synthetic-raw-output", useUnmergedTree = true).assertDoesNotExist()
    }

    private fun setActivityContent() {
        composeRule.setContent {
            HermesCelesteTheme {
                AgentActivityPanel(
                    projection = projection(),
                    reasoningDisclosureEnabled = true,
                    onReasoningDisclosureChange = {},
                )
            }
        }
    }

    private fun projection(): AgentActivityProjection {
        val safeOutput = DisplayedDetail(
            text = "[redacted]",
            originalLength = 20,
            wasTruncated = false,
            wasRedacted = true,
            canRestore = false,
        )
        val tool = ToolActivity(
            uiKey = "activity:stored-42:tool-id:call-1:occurrence:1",
            callId = "call-1",
            name = "terminal",
            phase = ToolPhase.Completed,
            input = null,
            output = safeOutput,
            startedAt = null,
            finishedAt = null,
            correlation = CorrelationQuality.ExactId,
        )
        val reasoning = ServerReasoningActivity(
            uiKey = "activity:stored-42:reasoning:server:occurrence:1",
            source = ReasoningSource.ServerSummary,
            phase = ReasoningPhase.Complete,
            text = DisplayedDetail(
                text = "Displayed server summary",
                originalLength = 24,
                wasTruncated = false,
                wasRedacted = false,
                canRestore = false,
            ),
            serverLabel = "Server-provided reasoning",
        )
        return initialActivityProjection(
            originKey = "https://hermes.test",
            profile = "default",
            storedSessionId = "stored-42",
            runtimeSessionId = "runtime-7",
        ).copy(
            items = listOf<ActivityItem>(tool, reasoning),
            source = ActivitySource.Live,
            capability = ActivityCapabilityState.ToolAndServerReasoning,
            presentation = ActivityPresentationState.Available,
            serverReasoningAllowed = true,
        )
    }

    private fun hasRole(expected: Role): SemanticsMatcher =
        SemanticsMatcher.expectValue(SemanticsProperties.Role, expected)

    private fun hasStateDescription(expected: String): SemanticsMatcher =
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, expected)

    private fun hasLiveRegion(expected: LiveRegionMode): SemanticsMatcher =
        SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, expected)
}
