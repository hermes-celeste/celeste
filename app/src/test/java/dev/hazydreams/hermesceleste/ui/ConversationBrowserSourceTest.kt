package dev.hazydreams.hermesceleste.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused source guards for the browser's accessibility and navigation contract.
 * SessionListSemanticsTest covers the executable row/state projection; these
 * assertions keep the Compose wiring and high-risk labels from disappearing.
 */
class ConversationBrowserSourceTest {
    @Test
    fun sessionRowsKeepStableIdentityAndButtonSemantics() {
        val source = mainSource("ui/sessions/SessionListScreen.kt")

        assertContains(source, ".clickable(enabled = enabled, role = Role.Button")
        assertContains(source, ".semantics(mergeDescendants = true)")
        assertContains(source, "role = Role.Button")
        assertContains(source, "Cannot open conversation: profile ownership is unavailable.")
        assertContains(source, "session.catalogRowKey(originKey)")
    }

    @Test
    fun browserControlsDescribeLoadedSearchRefreshAndCreationScope() {
        val source = mainSource("ui/sessions/SessionListScreen.kt")

        assertContains(source, "New conversation profile:")
        assertContains(source, "This does not filter the loaded conversation list.")
        assertContains(source, "all loaded profiles")
        assertContains(source, "Search loaded conversations")
        assertContains(source, "Search is limited to loaded conversations.")
        assertContains(source, "Refresh conversations")
        assertContains(source, "Retry conversation connection")
        assertContains(source, "SessionCatalogStatus.ActionInFlight")
        assertContains(source, "SessionCatalogStatus.Opening")
        assertContains(source, "onCancelOpening")
        assertContains(source, "announce = true")
        assertContains(source, "loadingMessage != null")
    }

    @Test
    fun chatAndRoutesExposeExplicitBrowserOpenAndBackSemantics() {
        val conversationSource = mainSource("ui/conversation/ConversationScreen.kt")
        val routesSource = mainSource("ui/CelesteRoutes.kt")

        assertContains(conversationSource, "onOpenBrowser: () -> Unit")
        assertContains(conversationSource, "Text(\"Conversations\"")
        assertContains(routesSource, "CelesteDestination.Conversations")
        assertContains(routesSource, "viewModel.openConversationBrowser()")
        assertContains(routesSource, "viewModel.closeConversationBrowser()")
        assertContains(routesSource, "BackHandler { closeBrowser() }")
        assertContains(routesSource, "ConnectionPhase.LoadingSessions")
    }

    @Test
    fun rowsExposeSelectedAndRunningStateToAccessibility() {
        val source = mainSource("ui/sessions/SessionListScreen.kt")

        assertContains(source, "activeSession: StoredSession? = null")
        assertContains(source, "activeSessionRunning: Boolean = false")
        assertContains(source, "selected = selectedKey != null")
        assertContains(source, "running = activeSessionRunning")
        assertContains(source, "sessionRowSemantics(")
        assertContains(source, "selected = semantics.selected")
        assertContains(source, "Open and selected")
        assertContains(source, "Open and running")
    }

    private fun assertContains(source: String, expected: String) {
        assertTrue("Expected UI source to contain: $expected", source.contains(expected))
    }

    private fun mainSource(relativePath: String): String {
        val candidates = listOf(
            File("app/src/main/java/dev/hazydreams/hermesceleste/$relativePath"),
            File("src/main/java/dev/hazydreams/hermesceleste/$relativePath"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("Could not locate main source $relativePath")
    }
}
