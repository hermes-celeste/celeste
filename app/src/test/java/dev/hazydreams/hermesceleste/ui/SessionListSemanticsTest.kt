package dev.hazydreams.hermesceleste.ui

import dev.hazydreams.hermesceleste.SessionCatalogReducer
import dev.hazydreams.hermesceleste.SessionCatalogRequest
import dev.hazydreams.hermesceleste.SessionCatalogState
import dev.hazydreams.hermesceleste.SessionCatalogStatus
import dev.hazydreams.hermesceleste.SessionScope
import dev.hazydreams.hermesceleste.network.StoredSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionListSemanticsTest {
    @Test
    fun selectedRunningRowExposesItsStateAndStableDescription() {
        val semantics = sessionRowSemantics(
            session = session(),
            showProfile = true,
            enabled = true,
            selected = true,
            running = true,
        )

        assertTrue(semantics.selected)
        assertEquals("Open and running", semantics.stateDescription)
        assertTrue(semantics.contentDescription.contains("Shared conversation"))
        assertTrue(semantics.contentDescription.contains("work"))
        assertTrue(semantics.contentDescription.contains("open"))
        assertTrue(semantics.contentDescription.contains("running"))
    }

    @Test
    fun unavailableRowAnnouncesDisabledState() {
        val semantics = sessionRowSemantics(
            session = session(),
            showProfile = true,
            enabled = false,
            selected = false,
            running = false,
        )

        assertFalse(semantics.selected)
        assertEquals("Unavailable", semantics.stateDescription)
        assertTrue(semantics.contentDescription.endsWith("Open conversation."))
    }

    @Test
    fun unknownProfileRowIsVisibleButNotOpenable() {
        val semantics = sessionRowSemantics(
            session = session(profile = ""),
            showProfile = true,
            enabled = false,
            selected = false,
            running = false,
        )

        assertEquals("Unavailable: profile ownership unavailable", semantics.stateDescription)
        assertTrue(semantics.contentDescription.contains("profile unavailable"))
        assertTrue(semantics.contentDescription.contains("Cannot open conversation"))
    }

    @Test
    fun catalogPresentationKeepsLoadingRefreshNoResultsAndStaleDistinct() {
        val scope = requireNotNull(SessionScope.from("https://hermes.example", "default"))
        val firstRequest = request(scope, 1)
        val loading = SessionCatalogReducer.begin(SessionCatalogState(), firstRequest, refreshing = false)
        assertEquals(SessionCatalogStatus.Loading, loading.status)

        val ready = SessionCatalogReducer.succeeded(
            loading,
            firstRequest,
            listOf(session()),
        )
        val noResults = ready.withQuery("missing").withSearchResults("missing", emptyList())
        assertEquals(SessionCatalogStatus.NoResults, noResults.status)

        val refreshRequest = request(scope, 2)
        val refreshing = SessionCatalogReducer.begin(ready, refreshRequest, refreshing = true)
        assertEquals(SessionCatalogStatus.Refreshing, refreshing.status)

        val stale = SessionCatalogReducer.failed(refreshing, refreshRequest, "offline")
        assertEquals(SessionCatalogStatus.Stale, stale.status)
    }

    private fun session(profile: String = "work") = StoredSession(
        id = "stored-42",
        title = "Shared conversation",
        preview = "Synthetic preview",
        startedAt = 1.0,
        messageCount = 3,
        source = "desktop",
        profile = profile,
    )

    private fun request(scope: SessionScope, generation: Long) = SessionCatalogRequest(
        scope = scope,
        originGeneration = 1,
        profileGeneration = 1,
        requestGeneration = generation,
        connectionAttempt = 1,
    )
}
