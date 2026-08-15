package dev.hazydreams.hermesceleste.ui.conversation

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.onNode
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.waitForIdle
import dev.hazydreams.hermesceleste.ActiveTurnAction
import dev.hazydreams.hermesceleste.ConversationActionModel
import dev.hazydreams.hermesceleste.ConversationActivityCandidate
import dev.hazydreams.hermesceleste.ConversationActivityCandidates
import dev.hazydreams.hermesceleste.TurnState
import dev.hazydreams.hermesceleste.network.ConversationMessage
import dev.hazydreams.hermesceleste.network.StoredSession
import dev.hazydreams.hermesceleste.ui.HermesCelesteTheme
import org.junit.Rule
import org.junit.Test

class ConversationActivityUiTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun mountedConversationReplacesActivityOwnerAcrossStreamingToolAndCompletion() {
        val fixture = mutableStateOf(ConversationFixture(turnState = TurnState.Running))
        mount(fixture)

        composeTestRule.mainClock.advanceTimeBy(300)
        composeTestRule.waitForIdle()
        activityNode("Working…", LiveRegionMode.Polite).assertExists()
        composeTestRule.onAllNodes(hasContentDescription("Working…"), useUnmergedTree = true)
            .assertCountEquals(1)
        composeTestRule.onNodeWithText("Stop response").assertExists()

        transition(
            fixture,
            ConversationFixture(
                turnState = TurnState.Running,
                streamingText = "partial response",
            ),
        )
        composeTestRule.onNodeWithText("Working…").assertDoesNotExist()
        activityNode("Hermes response in progress", LiveRegionMode.Polite).assertExists()
        composeTestRule.onNodeWithText("Stop response").assertExists()

        transition(
            fixture,
            ConversationFixture(
                turnState = TurnState.Running,
                messages = listOf(
                    ConversationMessage(
                        role = "tool",
                        text = "",
                        toolName = "terminal",
                        id = "tool-1",
                        pending = true,
                    ),
                ),
                activityCandidates = ConversationActivityCandidates(
                    pendingTool = ConversationActivityCandidate(
                        index = 0,
                        identity = "tool-1",
                        displayName = "terminal",
                    ),
                ),
            ),
        )
        composeTestRule.onNode(hasContentDescription("Hermes response in progress"), useUnmergedTree = true)
            .assertDoesNotExist()
        activityNode("Running terminal…", LiveRegionMode.Polite).assertExists()
        composeTestRule.onNodeWithText("Working…").assertDoesNotExist()

        transition(
            fixture,
            ConversationFixture(
                turnState = TurnState.Idle,
                messages = listOf(
                    ConversationMessage(
                        role = "assistant",
                        text = "Completed response",
                        id = "assistant-1",
                    ),
                ),
            ),
        )
        composeTestRule.onNode(hasContentDescription("Running terminal…"), useUnmergedTree = true)
            .assertDoesNotExist()
        composeTestRule.onNodeWithText("Working…").assertDoesNotExist()
        composeTestRule.onNodeWithText("Send message").assertExists()
    }

    @Test
    fun mountedConversationRendersActionLabelsAndRecoveryLiveRegions() {
        val fixture = mutableStateOf(
            ConversationFixture(
                turnState = TurnState.Running,
                draft = "draft",
                actionModel = ConversationActionModel(ActiveTurnAction.SteerWithMessage),
            ),
        )
        mount(fixture)

        composeTestRule.onNodeWithText("Steer with message").assertExists()

        transition(
            fixture,
            ConversationFixture(
                turnState = TurnState.Running,
                draft = "draft",
                actionModel = ConversationActionModel(ActiveTurnAction.QueueMessage),
            ),
        )
        composeTestRule.onNodeWithText("Queue message").assertExists()

        transition(
            fixture,
            ConversationFixture(
                turnState = TurnState.Reconnecting,
                draft = "draft",
            ),
        )
        composeTestRule.onNodeWithText("Retry connection").assertExists()
        activityNode("Reconnecting to Hermes…", LiveRegionMode.Polite).assertExists()

        transition(
            fixture,
            ConversationFixture(
                turnState = TurnState.Idle,
                errorMessage = "Hermes could not finish that response.",
            ),
        )
        composeTestRule.onNodeWithText("Retry").assertExists()
        activityNode("Hermes could not finish that response.", LiveRegionMode.Assertive).assertExists()

        transition(fixture, ConversationFixture(turnState = TurnState.Idle))
        composeTestRule.onNodeWithText("Retry").assertDoesNotExist()
        composeTestRule.onNodeWithText("Send message").assertExists()
        composeTestRule.onNode(hasContentDescription("Hermes could not finish that response."), useUnmergedTree = true)
            .assertDoesNotExist()
    }

    private fun mount(fixture: MutableState<ConversationFixture>) {
        composeTestRule.setContent {
            HermesCelesteTheme {
                val current = fixture.value
                ConversationScreen(
                    summary = testSession,
                    activityScope = testScope,
                    messages = current.messages,
                    streamingText = current.streamingText,
                    draft = current.draft,
                    turnState = current.turnState,
                    errorMessage = current.errorMessage,
                    activityCandidates = current.activityCandidates,
                    actionModel = current.actionModel,
                    onDraftChange = {},
                    onSend = {},
                    onInterrupt = {},
                    onReconnect = {},
                    onBack = {},
                )
            }
        }
    }

    private fun transition(
        fixture: MutableState<ConversationFixture>,
        next: ConversationFixture,
    ) {
        composeTestRule.runOnIdle { fixture.value = next }
        composeTestRule.waitForIdle()
    }

    private fun activityNode(description: String, mode: LiveRegionMode) =
        composeTestRule.onNode(
            hasContentDescription(description) and
                SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, mode),
            useUnmergedTree = true,
        )

    private data class ConversationFixture(
        val turnState: TurnState,
        val messages: List<ConversationMessage> = emptyList(),
        val streamingText: String = "",
        val draft: String = "",
        val errorMessage: String? = null,
        val activityCandidates: ConversationActivityCandidates = ConversationActivityCandidates(),
        val actionModel: ConversationActionModel = ConversationActionModel(),
    )

    private companion object {
        val testSession = StoredSession(
            id = "ui-test-session",
            title = "UI test conversation",
            preview = "",
            startedAt = 0.0,
            messageCount = 0,
            source = "android",
            profile = "default",
        )
        val testScope = ConversationActivityScope(
            gatewayOrigin = "https://hermes.example",
            profile = "default",
            sessionId = "ui-test-session",
        )
    }
}
