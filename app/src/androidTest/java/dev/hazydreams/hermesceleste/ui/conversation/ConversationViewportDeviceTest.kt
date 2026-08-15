package dev.hazydreams.hermesceleste.ui.conversation

import android.os.Build
import android.view.WindowInsets as AndroidWindowInsets
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertHasScrollAction
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNode
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.hazydreams.hermesceleste.TurnState
import dev.hazydreams.hermesceleste.ui.HermesCelesteTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationViewportDeviceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ConversationViewportTestActivity>()

    private val device: UiDevice by lazy {
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    }

    @Test
    fun visibleImeInsetKeepsComposerAndTerminalRowSeparated() {
        setSyntheticContent(bottomInsetPx = 320, topInsetPx = 24)
        assertTerminalClearsDock()
        composeRule.onNodeWithContentDescription("Message composer").assertIsDisplayed()
    }

    @Test
    fun navigationOnlyInsetUsesOneBottomOwner() {
        setSyntheticContent(bottomInsetPx = 48)
        assertTerminalClearsDock()
        composeRule.onNodeWithContentDescription("Conversation transcript").assertHasScrollAction()
    }

    @Test
    fun cutoutSafeDrawingKeepsHeaderAndActionsReachable() {
        setSyntheticContent(bottomInsetPx = 48, topInsetPx = 56)
        composeRule.onNodeWithText("←  Back").assertIsDisplayed()
        composeRule.onNodeWithText("Synthetic conversation").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Message composer").assertIsDisplayed()
    }

    @Test
    fun transcriptAndComposerExposeTalkBackSemantics() {
        composeRule.onNodeWithContentDescription("Conversation transcript").assertHasScrollAction()
        composeRule.onNodeWithContentDescription("Message composer").assertIsDisplayed()
        composeRule.onNodeWithText("←  Back").assertIsDisplayed()
        composeRule.onNodeWithText("Send  →").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Terminal transcript row").assertIsDisplayed()
    }

    @Test
    fun largeFontScaleKeepsAllComposerActionsContentSized() {
        composeRule.runOnUiThread { composeRule.activity.setContentFontScale(2f) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Send  →").assertIsDisplayed()

        composeRule.runOnUiThread {
            composeRule.activity.setContentTurnState(TurnState.Running)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Stop").assertIsDisplayed()

        composeRule.runOnUiThread {
            composeRule.activity.setContentTurnState(TurnState.Reconnecting)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Retry").assertIsDisplayed()
    }

    @Test
    fun systemBackDismissesImeBeforeLeavingConversation() {
        composeRule.runOnUiThread { composeRule.activity.useProductionInsets() }
        composeRule.waitForIdle()
        val composer = composeRule.onNode(hasSetTextAction())
        composer.performClick()
        composer.performTextInput("synthetic keyboard text")
        composeRule.waitForIdle()

        var imeVisible = false
        composeRule.runOnIdle {
            imeVisible = composeRule.activity.window.decorView.rootWindowInsets
                ?.isVisible(AndroidWindowInsets.Type.ime()) == true
        }
        assumeTrue("This gate requires a real visible IME.", imeVisible)

        device.pressBack()
        composeRule.waitForIdle()
        assertEquals("The first system Back must stay in the conversation.", 0, composeRule.activity.backCalls)

        device.pressBack()
        composeRule.waitForIdle()
        assertEquals("The second system Back delegates to the route.", 1, composeRule.activity.backCalls)
    }

    @Test
    fun productionInsetsTrackImeOpenAndClose() {
        composeRule.runOnUiThread { composeRule.activity.useProductionInsets() }
        composeRule.waitForIdle()
        val composer = composeRule.onNode(hasSetTextAction())
        composer.performClick()
        composer.performTextInput("production inset keyboard text")
        composeRule.waitForIdle()

        var imeVisible = false
        composeRule.runOnIdle {
            imeVisible = composeRule.activity.window.decorView.rootWindowInsets
                ?.isVisible(AndroidWindowInsets.Type.ime()) == true
        }
        assumeTrue("This gate requires a real visible IME.", imeVisible)
        assertProductionBoundsAgainstWindowInsets()

        device.pressBack()
        composeRule.waitForIdle()
        var imeHidden = false
        composeRule.runOnIdle {
            imeHidden = composeRule.activity.window.decorView.rootWindowInsets
                ?.isVisible(AndroidWindowInsets.Type.ime()) == false
        }
        assertTrue("The production IME inset must settle after Back.", imeHidden)
        assertProductionBoundsAgainstWindowInsets()
    }

    @Test
    fun productionInsetsKeepComposerGrowthAndTerminalBoundsUsable() {
        composeRule.runOnUiThread {
            composeRule.activity.useProductionInsets()
            composeRule.activity.setContentDraft(longComposerDraft())
        }
        composeRule.waitForIdle()
        assertTerminalClearsDock()
        assertProductionBoundsAgainstWindowInsets()
    }

    @Test
    fun rotationKeepsTheMeasuredDockAndTranscriptUsable() {
        try {
            device.setOrientationLeft()
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("Message composer").assertIsDisplayed()
            composeRule.onNodeWithContentDescription("Conversation transcript").assertIsDisplayed()
        } finally {
            device.unfreezeRotation()
            device.setOrientationNatural()
        }
    }

    @Test
    fun rotationRestoresHistoryModeAndAnchorForTheSameSession() {
        composeRule.onNodeWithContentDescription("Conversation transcript")
            .performScrollToIndex(6)
        composeRule.waitForIdle()
        composeRule.onNodeWithText(syntheticRowText(6)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Jump to latest").assertIsDisplayed()

        try {
            device.setOrientationLeft()
            composeRule.waitForIdle()
            composeRule.onNodeWithText(syntheticRowText(6)).assertIsDisplayed()
            composeRule.onNodeWithContentDescription("Jump to latest").assertIsDisplayed()
        } finally {
            device.unfreezeRotation()
            device.setOrientationNatural()
        }
    }

    @Test
    fun cutoutDeviceKeepsSafeDrawingHeaderClearance() {
        assumeTrue("This gate requires a display cutout device.", Build.VERSION.SDK_INT >= 28)
        composeRule.runOnUiThread { composeRule.activity.useProductionInsets() }
        composeRule.waitForIdle()
        var hasCutout = false
        composeRule.runOnIdle {
            hasCutout = composeRule.activity.window.decorView.rootWindowInsets?.displayCutout != null
        }
        assumeTrue("This gate requires a display cutout configuration.", hasCutout)
        composeRule.onNodeWithText("←  Back").assertIsDisplayed()
        composeRule.onNodeWithText("Synthetic conversation").assertIsDisplayed()
    }

    @Test
    fun gestureNavigationUsesTheMeasuredGestureCategory() {
        composeRule.runOnUiThread { composeRule.activity.useProductionInsets() }
        composeRule.waitForIdle()
        val insets = currentRootInsetsOrSkip()
        val navigationBottom = insets.getInsets(AndroidWindowInsets.Type.navigationBars()).bottom
        val gestureBottom = insets.getInsets(AndroidWindowInsets.Type.systemGestures()).bottom
        assumeTrue("This gate requires gesture navigation.", gestureBottom > navigationBottom)
        assertTrue(activeBottomOcclusionPx(0, navigationBottom, gestureBottom) >= gestureBottom)
        assertTerminalClearsDock()
        assertProductionBoundsAgainstWindowInsets()
    }

    @Test
    fun threeButtonNavigationUsesTheMeasuredNavigationCategory() {
        composeRule.runOnUiThread { composeRule.activity.useProductionInsets() }
        composeRule.waitForIdle()
        val insets = currentRootInsetsOrSkip()
        val navigationBottom = insets.getInsets(AndroidWindowInsets.Type.navigationBars()).bottom
        val gestureBottom = insets.getInsets(AndroidWindowInsets.Type.systemGestures()).bottom
        assumeTrue("This gate requires three-button navigation.", navigationBottom > gestureBottom)
        assertTrue(activeBottomOcclusionPx(0, navigationBottom, gestureBottom) >= navigationBottom)
        assertTerminalClearsDock()
        assertProductionBoundsAgainstWindowInsets()
    }

    @Test
    fun repeatedAndCancelledImeTransitionsRetryUntilInsetsSettle() {
        updateContent {
            setContentInsets(bottom = 320, settled = false)
        }
        updateContent {
            setContentInsets(bottom = 0, settled = false)
        }
        updateContent {
            setContentInsets(bottom = 48, settled = true)
        }

        composeRule.onNodeWithContentDescription("Message composer").assertIsDisplayed()
        assertTerminalClearsDock()
    }

    @Test
    fun earlyAccessibilityScrollAwayInvalidatesInitialLatestSettling() {
        val settled = mutableStateOf(false)
        composeRule.setContent {
            HermesCelesteTheme {
                val draft = remember { mutableStateOf("synthetic draft") }
                ConversationScreen(
                    summary = syntheticSummary,
                    messages = syntheticMessages,
                    streamingText = "",
                    draft = draft.value,
                    turnState = TurnState.Idle,
                    loadingMessage = null,
                    errorMessage = null,
                    onDraftChange = { draft.value = it },
                    onSend = {},
                    onInterrupt = {},
                    onReconnect = {},
                    onBack = {},
                    bottomOcclusion = WindowInsets(0, 0, 0, 48),
                    safeDrawingInsets = WindowInsets(16, 0, 16, 0),
                    bottomOcclusionSettled = settled.value,
                )
            }
        }

        composeRule.onNodeWithContentDescription("Conversation transcript")
            .performScrollToIndex(6)
        composeRule.waitForIdle()
        composeRule.runOnUiThread { settled.value = true }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(syntheticRowText(6)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Jump to latest").assertIsDisplayed()
    }

    @Test
    fun streamingAndTallRowsFollowLatestButPreserveAHistoryReader() {
        composeRule.onNodeWithContentDescription("Terminal transcript row").assertIsDisplayed()

        updateContent {
            setContentStreamingText(longStreamingText())
        }
        composeRule.onNodeWithContentDescription("Terminal transcript row").assertIsDisplayed()
        assertTerminalClearsDock()

        val transcript = composeRule.onNodeWithContentDescription("Conversation transcript")
        transcript.performScrollToIndex(5)
        composeRule.waitForIdle()
        composeRule.onNodeWithText(syntheticRowText(5)).assertIsDisplayed()

        updateContent {
            setContentStreamingText(longStreamingText() + " newer delta")
        }
        composeRule.onNodeWithText(syntheticRowText(5)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Jump to latest").assertIsDisplayed()
    }

    @Test
    fun removedHistoryAnchorFallsBackSafelyAndKeepsJumpRecoveryAvailable() {
        composeRule.onNodeWithContentDescription("Conversation transcript")
            .performScrollToIndex(18)
        composeRule.waitForIdle()

        updateContent {
            setContentMessages(syntheticMessages.take(4))
            setContentProjectionGeneration(1L)
        }

        composeRule.onNodeWithContentDescription("Terminal transcript row").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Jump to latest").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Jump to latest").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Terminal transcript row").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Jump to latest").assertDoesNotExist()
    }

    @Test
    fun reconnectProjectionGenerationCancelsStaleTransitionAndPreservesHistory() {
        composeRule.onNodeWithContentDescription("Conversation transcript")
            .performScrollToIndex(6)
        composeRule.waitForIdle()
        composeRule.onNodeWithText(syntheticRowText(6)).assertIsDisplayed()

        updateContent {
            setContentInsets(bottom = 320, settled = false)
            setContentProjectionGeneration(1L)
        }
        updateContent {
            setContentProjectionGeneration(2L)
            setContentInsets(bottom = 48, settled = true)
        }

        composeRule.onNodeWithText(syntheticRowText(6)).assertIsDisplayed()
    }

    @Test
    fun switchingSessionsDisposesPendingJumpWorkBeforeRenderingTheNewList() {
        composeRule.onNodeWithContentDescription("Conversation transcript")
            .performScrollToIndex(4)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Jump to latest").performClick()

        updateContent {
            setContentSummary(
                syntheticSummary.copy(
                    id = "instrumentation-session-two",
                    title = "Second synthetic conversation",
                ),
            )
            setContentMessages(syntheticMessages.takeLast(6))
        }

        composeRule.onNodeWithText("Second synthetic conversation").assertIsDisplayed()
        composeRule.onNodeWithText("Synthetic conversation").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Terminal transcript row").assertIsDisplayed()
    }

    @Test
    fun scrollingToEndRelatchesAgainstTheMeasuredDockBoundary() {
        setSyntheticContent(bottomInsetPx = 48)
        val transcript = composeRule.onNodeWithContentDescription("Conversation transcript")
        transcript.performScrollToIndex(6)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Jump to latest").assertIsDisplayed()

        transcript.performScrollToIndex(syntheticMessages.lastIndex)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Jump to latest").assertDoesNotExist()
    }

    @Test
    fun reconnectErrorKeepsDraftAndRetryActionReachable() {
        composeRule.onNodeWithContentDescription("Conversation transcript")
            .performScrollToIndex(5)
        composeRule.waitForIdle()
        updateContent {
            setContentDraft("draft survives reconnect")
            setContentTurnState(TurnState.Reconnecting)
            setContentStatus(
                loading = "Reconnecting to Hermes…",
                error = "Synthetic reconnect error",
            )
        }

        composeRule.onNodeWithText("draft survives reconnect").assertIsDisplayed()
        composeRule.onNodeWithText("Synthetic reconnect error").assertIsDisplayed()
        composeRule.onNodeWithText(syntheticRowText(5)).assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()
        assertEquals(1, composeRule.activity.reconnectCalls)
    }

    private fun setSyntheticContent(bottomInsetPx: Int, topInsetPx: Int = 0) {
        composeRule.setContent {
            HermesCelesteTheme {
                val draft = remember { mutableStateOf("synthetic draft") }
                ConversationScreen(
                    summary = syntheticSummary,
                    messages = syntheticMessages,
                    streamingText = "",
                    draft = draft.value,
                    turnState = TurnState.Idle,
                    loadingMessage = null,
                    errorMessage = null,
                    onDraftChange = { draft.value = it },
                    onSend = {},
                    onInterrupt = {},
                    onReconnect = {},
                    onBack = {},
                    bottomOcclusion = WindowInsets(0, 0, 0, bottomInsetPx),
                    safeDrawingInsets = WindowInsets(16, topInsetPx, 16, 0),
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun updateContent(update: ConversationViewportTestActivity.() -> Unit) {
        composeRule.runOnUiThread {
            composeRule.activity.update()
        }
        composeRule.waitForIdle()
    }

    private fun syntheticRowText(index: Int): String =
        "Synthetic transcript row $index. This content exists only to exercise viewport geometry."

    private fun longStreamingText(): String =
        (0 until 80).joinToString(" ") { index ->
            "Synthetic streaming paragraph $index grows the terminal row."
        }

    private fun assertTerminalClearsDock() {
        val terminalBottom = composeRule.onNodeWithContentDescription("Terminal transcript row")
            .getUnclippedBoundsInRoot()
            .bottom
        val dockTop = composeRule.onNodeWithContentDescription("Message composer")
            .getUnclippedBoundsInRoot()
            .top
        assertTrue(
            "The terminal row must clear the measured composer dock.",
            terminalBottom <= dockTop,
        )
    }

    private fun assertProductionBoundsAgainstWindowInsets() {
        assumeTrue("Production inset bounds require API 30+.", Build.VERSION.SDK_INT >= 30)
        var rootHeight = 0
        var rootInsets: android.view.WindowInsets? = null
        composeRule.runOnIdle {
            rootHeight = composeRule.activity.window.decorView.height
            rootInsets = composeRule.activity.window.decorView.rootWindowInsets
        }
        assumeTrue("The production test window did not report WindowInsets.", rootInsets != null)
        val insets = rootInsets!!
        val activeBottom = activeBottomOcclusionPx(
            imeBottomPx = insets.getInsets(AndroidWindowInsets.Type.ime()).bottom,
            navigationBottomPx = insets.getInsets(AndroidWindowInsets.Type.navigationBars()).bottom,
            systemGesturesBottomPx = insets.getInsets(AndroidWindowInsets.Type.systemGestures()).bottom,
        )
        val usableBottom = rootHeight - activeBottom
        val composerBounds = composeRule.onNodeWithContentDescription("Message composer")
            .getUnclippedBoundsInRoot()
        val fieldBounds = composeRule.onNode(hasSetTextAction()).getUnclippedBoundsInRoot()
        val sendBounds = composeRule.onNodeWithText("Send  →").getUnclippedBoundsInRoot()
        val terminalBounds = composeRule.onNodeWithContentDescription("Terminal transcript row")
            .getUnclippedBoundsInRoot()
        assertTrue("The text field must remain above the real bottom occlusion.", fieldBounds.bottom <= usableBottom + 2f)
        assertTrue("The composer action must remain above the real bottom occlusion.", sendBounds.bottom <= usableBottom + 2f)
        assertTrue("The terminal row must clear the measured production dock.", terminalBounds.bottom <= composerBounds.top + 2f)
    }

    private fun longComposerDraft(): String =
        (1..5).joinToString("\n") { "Synthetic composer line $it" }

    private fun currentRootInsetsOrSkip(): android.view.WindowInsets {
        assumeTrue("Navigation mode inspection requires API 30+.", Build.VERSION.SDK_INT >= 30)
        var insets: android.view.WindowInsets? = null
        composeRule.runOnIdle {
            insets = composeRule.activity.window.decorView.rootWindowInsets
        }
        assumeTrue("The test window did not report WindowInsets.", insets != null)
        return insets!!
    }
}
