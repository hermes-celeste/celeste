package dev.hazydreams.hermesceleste.ui.conversation

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.waitForIdle
import dev.hazydreams.hermesceleste.RuntimeControlsLifecycle
import dev.hazydreams.hermesceleste.RuntimeControlsUiState
import dev.hazydreams.hermesceleste.TurnState
import dev.hazydreams.hermesceleste.network.ConversationMessage
import dev.hazydreams.hermesceleste.network.RuntimeControlsCapabilities
import dev.hazydreams.hermesceleste.network.RuntimeControlsSnapshot
import dev.hazydreams.hermesceleste.network.RuntimeModelOption
import dev.hazydreams.hermesceleste.network.StoredSession
import dev.hazydreams.hermesceleste.ui.HermesCelesteTheme
import org.junit.Rule
import org.junit.Test

class RuntimeControlsComposeAccessibilityTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun dismissingRuntimeControlsSheetRestoresFocusToThePill() {
        val controls = mutableStateOf(testRuntimeControls)
        mount(controls)

        val pill = composeTestRule.onNodeWithTag("runtime-controls-pill")
        pill.assert(hasContentDescription("nous / gpt-5.6-sol, Reasoning high, change settings"))
        pill.performSemanticsAction(SemanticsActions.RequestFocus)
        pill.assertIsFocused()

        pill.performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("runtime-controls-sheet").assertExists()
        val cancel = composeTestRule.onNodeWithTag("runtime-controls-cancel")
        cancel.performSemanticsAction(SemanticsActions.RequestFocus)
        cancel.assertIsFocused()

        cancel.performClick()
        composeTestRule.waitForIdle()
        pill.assertIsFocused()
    }

    private fun mount(controls: MutableState<RuntimeControlsUiState>) {
        composeTestRule.setContent {
            HermesCelesteTheme {
                ConversationScreen(
                    summary = testSession,
                    messages = emptyList<ConversationMessage>(),
                    streamingText = "",
                    draft = "",
                    turnState = TurnState.Idle,
                    runtimeControls = controls.value,
                    loadingMessage = null,
                    errorMessage = null,
                    onDraftChange = {},
                    onSend = {},
                    onInterrupt = {},
                    onReconnect = {},
                    onRuntimeControlsOpen = {
                        controls.value = controls.value.copy(pickerOpen = true)
                    },
                    onRuntimeModelSelected = { _, _ -> },
                    onRuntimeReasoningSelected = {},
                    onRuntimeControlsApply = {},
                    onRuntimeControlsCancel = {
                        controls.value = controls.value.copy(pickerOpen = false)
                    },
                    onBack = {},
                )
            }
        }
        composeTestRule.waitForIdle()
    }

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
        val testRuntimeControls = RuntimeControlsUiState(
            lifecycle = RuntimeControlsLifecycle.Available,
            snapshot = RuntimeControlsSnapshot(
                origin = "https://hermes.example",
                profile = "default",
                storedSessionId = "ui-test-session",
                runtimeSessionId = "runtime-session",
                provider = "nous",
                model = "gpt-5.6-sol",
                reasoningEffort = "high",
                capabilities = RuntimeControlsCapabilities(
                    available = true,
                    modelOptions = listOf(
                        RuntimeModelOption(provider = "nous", model = "gpt-5.6-sol"),
                    ),
                    reasoningEfforts = listOf("high"),
                ),
            ),
        )
    }
}
