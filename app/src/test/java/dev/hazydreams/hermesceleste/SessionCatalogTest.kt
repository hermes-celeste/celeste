package dev.hazydreams.hermesceleste

import dev.hazydreams.hermesceleste.network.StoredSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCatalogTest {
    private val origin = "https://hermes.example"

    @Test
    fun sessionKeyUsesNormalizedOriginProfileAndDurableIdOnly() {
        val first = SessionKey.from("HTTPS://Hermes.Example/", " Work ", " stored-1 ")
        val second = SessionKey.from("https://hermes.example", "work", "stored-1")

        assertEquals(second, first)
        assertEquals("stored-1", first?.durableId)
        assertFalse(first?.durableId == "runtime-1")
    }

    @Test
    fun profileScopeKeepsServerOrderAndDeduplicatesRows() {
        val scope = requireNotNull(SessionScope.from(origin, "work"))
        val rows = listOf(
            row("work-first", profile = "work"),
            row("default-row"),
            row("work-first", profile = "work"),
        )

        val filtered = SessionCatalogReducer.filterAuthoritativeRows(scope, rows)

        assertEquals(listOf("work-first"), filtered.map(StoredSession::id))
    }

    @Test
    fun sessionScopeRejectsAKeyFromAnotherOrigin() {
        val scope = requireNotNull(SessionScope.from(origin, "work"))
        val foreignKey = requireNotNull(SessionKey.from("https://other.example", "work", "stored-1"))

        assertFalse(scope.accepts(foreignKey))
    }

    @Test
    fun profileScopeExcludesForeignProfileWithoutReorderingMatches() {
        val scope = requireNotNull(SessionScope.from(origin, "work"))
        val rows = listOf(
            row("work-first", profile = "work"),
            row("default-row"),
            row("work-second", profile = "work"),
        )

        assertEquals(
            listOf("work-first", "work-second"),
            SessionCatalogReducer.filterAuthoritativeRows(scope, rows).map(StoredSession::id),
        )
    }

    @Test
    fun lateRequestCannotPublishAfterANewerRequestBegan() {
        val scope = requireNotNull(SessionScope.from(origin, "work"))
        val first = request(scope, requestGeneration = 1)
        val second = request(scope, requestGeneration = 2)
        val loading = SessionCatalogReducer.begin(SessionCatalogState(), first, refreshing = false)
        val newer = SessionCatalogReducer.begin(loading, second, refreshing = false)

        val published = SessionCatalogReducer.succeeded(newer, first, listOf(row("late")))

        assertEquals(newer, published)
        assertEquals(SessionCatalogStatus.Loading, published.phase)
        assertTrue(published.rows.isEmpty())
    }

    @Test
    fun refreshFailureRetainsRowsAsStaleButInitialFailureIsError() {
        val scope = requireNotNull(SessionScope.from(origin, "work"))
        val initialRequest = request(scope, requestGeneration = 1)
        val loading = SessionCatalogReducer.begin(SessionCatalogState(), initialRequest, refreshing = false)
        val initialFailure = SessionCatalogReducer.failed(loading, initialRequest, "offline")
        assertEquals(SessionCatalogStatus.Error, initialFailure.phase)

        val readyRequest = request(scope, requestGeneration = 2)
        val ready = SessionCatalogReducer.succeeded(
            SessionCatalogReducer.begin(initialFailure, readyRequest, refreshing = false),
            readyRequest,
            listOf(row("known")),
        )
        val refreshRequest = request(scope, requestGeneration = 3)
        val refreshing = SessionCatalogReducer.begin(ready, refreshRequest, refreshing = true)
        val stale = SessionCatalogReducer.failed(refreshing, refreshRequest, "offline")

        assertEquals(SessionCatalogStatus.Stale, stale.phase)
        assertEquals(listOf("known"), stale.rows.map(StoredSession::id))
    }

    @Test
    fun lateResponseFromAnotherProfileCannotPublishIntoTheCurrentScope() {
        val defaultScope = requireNotNull(SessionScope.from(origin, "default"))
        val workScope = requireNotNull(SessionScope.from(origin, "work"))
        val first = request(defaultScope, requestGeneration = 1)
        val second = request(workScope, requestGeneration = 2)
        val loading = SessionCatalogReducer.begin(SessionCatalogState(), first, refreshing = false)
        val switching = SessionCatalogReducer.begin(loading, second, refreshing = false)

        val published = SessionCatalogReducer.succeeded(
            switching,
            first,
            listOf(row("late-default", profile = "default")),
        )

        assertEquals(switching, published)
        assertEquals(workScope, published.scope)
        assertTrue(published.rows.isEmpty())
    }

    @Test
    fun reconnectingAndStaleStatesAreNotMaskedByNoResults() {
        val scope = requireNotNull(SessionScope.from(origin, "default"))
        val initial = request(scope, requestGeneration = 1)
        val ready = SessionCatalogReducer.succeeded(
            SessionCatalogReducer.begin(SessionCatalogState(), initial, refreshing = false),
            initial,
            listOf(row("known")),
        ).withQuery("missing").withSearchResults("missing", emptyList())
        val refreshingRequest = request(scope, requestGeneration = 2)
        val refreshing = SessionCatalogReducer.begin(ready, refreshingRequest, refreshing = true)

        assertEquals(SessionCatalogStatus.Refreshing, refreshing.status)
        val stale = SessionCatalogReducer.failed(refreshing, refreshingRequest, "offline")
        assertEquals(SessionCatalogStatus.Stale, stale.status)
    }

    @Test
    fun reapplyingTheSameQueryDoesNotDiscardDebouncedResults() {
        val scope = requireNotNull(SessionScope.from(origin, "default"))
        val row = row("known", title = "Known conversation")
        val state = SessionCatalogState(
            phase = SessionCatalogStatus.Ready,
            scope = scope,
            rows = listOf(row),
            query = "known",
            queryResults = listOf(row),
            queryInFlight = false,
        )

        assertEquals(state, state.withQuery("known"))
        assertEquals(listOf("known"), state.withQuery("known").filteredRows.map(StoredSession::id))
    }

    @Test
    fun loadedWindowSearchMatchesSafeFieldsWithoutChangingOrder() {
        val rows = listOf(
            row("first", title = "Build notes", preview = "Deploy the gateway", source = "desktop"),
            row("second", title = "Unrelated", profile = "work", source = "cli"),
            row("third", title = "Another"),
        )

        assertEquals(
            listOf("first", "second"),
            searchLoadedSessions(rows, "gateway").map(StoredSession::id) +
                searchLoadedSessions(rows, "work").map(StoredSession::id),
        )
        assertEquals(rows, searchLoadedSessions(rows, ""))
    }

    @Test
    fun aliasIndexResolvesOnlyWithinTheVerifiedScope() {
        val work = requireNotNull(SessionScope.from(origin, "work"))
        val default = requireNotNull(SessionScope.from(origin, "default"))
        val workKey = requireNotNull(SessionKey.from(origin, "work", "stored-1"))
        val defaultKey = requireNotNull(SessionKey.from(origin, "default", "stored-1"))
        val aliases = SessionAliasIndex()
        aliases.replace(
            work,
            listOf(VerifiedSessionIdentity(workKey, aliases = setOf("runtime-work"))),
        )
        aliases.replace(
            default,
            listOf(VerifiedSessionIdentity(defaultKey, aliases = setOf("runtime-default"))),
        )

        assertEquals(workKey, aliases.resolve(work, "runtime-work"))
        assertEquals(defaultKey, aliases.resolve(default, "runtime-default"))
        assertEquals(null, aliases.resolve(work, "runtime-default"))
    }

    private fun request(scope: SessionScope, requestGeneration: Long): SessionCatalogRequest =
        SessionCatalogRequest(
            scope = scope,
            originGeneration = 1,
            profileGeneration = 1,
            requestGeneration = requestGeneration,
            connectionAttempt = 1,
        )

    private fun row(
        id: String,
        title: String = id,
        preview: String = "",
        profile: String = "default",
        source: String = "desktop",
    ) = StoredSession(
        id = id,
        title = title,
        preview = preview,
        startedAt = 1.0,
        messageCount = 1,
        source = source,
        profile = profile,
    )
}
