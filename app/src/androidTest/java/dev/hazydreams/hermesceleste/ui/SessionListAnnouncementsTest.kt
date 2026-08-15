package dev.hazydreams.hermesceleste.ui

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

import dev.hazydreams.hermesceleste.SessionCatalogState
import dev.hazydreams.hermesceleste.SessionCatalogStatus
import dev.hazydreams.hermesceleste.SessionScope
import dev.hazydreams.hermesceleste.UiNotice
import dev.hazydreams.hermesceleste.keyFor
import dev.hazydreams.hermesceleste.network.DashboardProfile
import dev.hazydreams.hermesceleste.network.StoredSession
import dev.hazydreams.hermesceleste.ui.sessions.SessionListScreen

@RunWith(AndroidJUnit4::class)
class SessionListAnnouncementsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun loadingAnnouncementIsAPoliteLiveRegion() {
        assertAnnouncement(
            state = SessionCatalogState(
                phase = SessionCatalogStatus.Loading,
                scope = scope,
            ),
            message = "Loading conversations…",
        )
    }

    @Test
    fun refreshAnnouncementIsAPoliteLiveRegion() {
        assertAnnouncement(
            state = SessionCatalogState(
                phase = SessionCatalogStatus.Refreshing,
                scope = scope,
                rows = listOf(knownSession),
            ),
            message = "Refreshing conversations…",
        )
    }

    @Test
    fun staleAnnouncementIsAPoliteLiveRegionAndKeepsRetry() {
        assertAnnouncement(
            state = SessionCatalogState(
                phase = SessionCatalogStatus.Stale,
                scope = scope,
                rows = listOf(knownSession),
                notice = UiNotice.CatalogUnavailable,
            ),
            message = UiNotice.CatalogUnavailable.message,
        )
        composeRule.onNodeWithText("Retry").assertExists()
    }

    @Test
    fun noResultsAnnouncementIsAPoliteLiveRegionAndKeepsClearAction() {
        assertAnnouncement(
            state = SessionCatalogState(
                phase = SessionCatalogStatus.Ready,
                scope = scope,
                rows = listOf(knownSession),
                query = "missing",
                queryResults = emptyList(),
            ),
            query = "missing",
            message = "No loaded conversation matches that search.",
        )
        composeRule.onNodeWithText("Clear search").assertExists()
    }

    @Test
    fun reconnectingAnnouncementIsAPoliteLiveRegionAndKeepsRetry() {
        assertAnnouncement(
            state = SessionCatalogState(
                phase = SessionCatalogStatus.Reconnecting,
                scope = scope,
                rows = listOf(knownSession),
            ),
            message = "Reconnecting conversation scope…",
        )
        composeRule.onNodeWithText("Retry").assertExists()
    }

    @Test
    fun actionAnnouncementIsAPoliteLiveRegion() {
        assertAnnouncement(
            state = SessionCatalogState(
                phase = SessionCatalogStatus.ActionInFlight,
                scope = scope,
                rows = listOf(knownSession),
            ),
            message = "Saving conversation…",
            loadingMessage = "Saving conversation…",
        )
    }

    @Test
    fun openingAnnouncementIsAPoliteLiveRegionAndKeepsCancel() {
        assertAnnouncement(
            state = SessionCatalogState(
                phase = SessionCatalogStatus.Opening,
                scope = scope,
                rows = listOf(knownSession),
                openingKey = requireNotNull(knownSession.keyFor(scope.originKey)),
            ),
            message = "Opening conversation…",
        )
        composeRule.onNodeWithText("Cancel").assertExists()
    }

    private fun assertAnnouncement(
        state: SessionCatalogState,
        message: String,
        query: String = "",
        loadingMessage: String? = null,
    ) {
        composeRule.setContent {
            HermesCelesteTheme {
                SessionListScreen(
                    sessions = state.rows,
                    profiles = listOf(DashboardProfile(name = "default", isDefault = true)),
                    selectedProfile = "default",
                    loadingMessage = loadingMessage,
                    notice = null,
                    onProfileSelected = {},
                    onNewConversation = {},
                    onSessionSelected = {},
                    onSettings = {},
                    catalogState = state,
                    query = query,
                    onQueryChange = {},
                    onRefresh = {},
                    onRetry = {},
                )
            }
        }
        composeRule
            .onNodeWithText(message, useUnmergedTree = true)
            .assertExists()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite,
                ),
            )
    }

    private companion object {
        val scope = requireNotNull(SessionScope.from("https://hermes.example", "default"))
        val knownSession = StoredSession(
            id = "stored-42",
            title = "Shared conversation",
            preview = "Synthetic preview",
            startedAt = 1.0,
            messageCount = 3,
            source = "desktop",
            profile = "default",
        )
    }
}
