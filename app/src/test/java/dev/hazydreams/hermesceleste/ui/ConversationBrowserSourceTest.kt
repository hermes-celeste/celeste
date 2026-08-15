package dev.hazydreams.hermesceleste.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused source checks for the browser's accessibility and navigation contract.
 * The JVM test source set has no Compose semantics runtime, so these assertions
 * keep the high-risk labels and roles from disappearing during UI refactors.
 */
class ConversationBrowserSourceTest {
    @Test
    fun sessionRowsKeepStableIdentityAndButtonSemantics() {
        val source = mainSource("ui/sessions/SessionListScreen.kt")

        assertContains(source, ".clickable(enabled = enabled, role = Role.Button")
        assertContains(source, ".semantics(mergeDescendants = true)")
        assertContains(source, "role = Role.Button")
        assertContains(source, """contentDescription = "${'$'}title. ${'$'}metadata. Open conversation.""")
        assertContains(source, "sessionRowKey(session, catalog.scope?.originKey.orEmpty())")
        assertContains(source, "${'$'}originKey\\u0000${'$'}{session.profile.trim().lowercase(Locale.ROOT)}\\u0000${'$'}{session.id.trim()}")
    }

    @Test
    fun browserControlsDescribeLoadedSearchRefreshAndCreationScope() {
        val source = mainSource("ui/sessions/SessionListScreen.kt")

        assertContains(source, "New conversation profile:")
        assertContains(source, "This also scopes the loaded conversation window")
        assertContains(source, "Profile scope:")
        assertContains(source, "Search loaded conversations")
        assertContains(source, "Search is limited to loaded conversations.")
        assertContains(source, "Refresh conversations")
        assertContains(source, "Retry conversation connection")
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
        assertContains(source, "selected = selected")
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
