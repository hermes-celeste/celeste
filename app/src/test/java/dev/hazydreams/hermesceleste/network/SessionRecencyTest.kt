package dev.hazydreams.hermesceleste.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRecencyTest {
    @Test
    fun acceptsOnlyFinitePositiveEpochSeconds() {
        assertEquals(42.5, requireNotNull(validEpochSeconds(42.5)), 0.0)
        listOf(
            null,
            0.0,
            -1.0,
            Double.NaN,
            Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY,
        ).forEach { value ->
            assertNull("Expected invalid epoch value: $value", validEpochSeconds(value))
        }
    }

    @Test
    fun ordersByLastActiveThenStartedAtThenDurableId() {
        val rows = listOf(
            session(id = "z", startedAt = 10.0, lastActive = 200.0),
            session(id = "a", startedAt = 30.0, lastActive = 200.0),
            session(id = "b", startedAt = 30.0, lastActive = 200.0),
            session(id = "old", startedAt = 500.0, lastActive = 100.0),
        )

        assertEquals(
            listOf("b", "a", "z", "old"),
            orderSessions(rows).map(StoredSession::id),
        )
    }

    @Test
    fun fallsBackToValidStartedAtAndLeavesUnknownRowsLast() {
        val rows = listOf(
            session(id = "a-unknown", startedAt = 0.0, lastActive = 0.0),
            session(id = "started", startedAt = 20.0, lastActive = null),
            session(id = "z-invalid", startedAt = Double.NaN, lastActive = -1.0),
        )

        assertEquals(
            listOf("started", "a-unknown", "z-invalid"),
            orderSessions(rows).map(StoredSession::id),
        )
    }

    @Test
    fun preservesArrivalOrderWhenACompatibilityPageHasNoNumericActivity() {
        val rows = listOf(
            session(id = "first", startedAt = 0.0, lastActive = null),
            session(id = "second", startedAt = 0.0, lastActive = null),
        )

        assertEquals(
            listOf("first", "second"),
            orderSessions(rows, ordering = SessionOrdering.SERVER_ORDER).map(StoredSession::id),
        )
    }

    @Test
    fun compatibilityOrderingMovesOnlyOptimisticallyActiveRows() {
        val origin = "https://hermes.test"
        val rows = listOf(
            session(id = "server-first", startedAt = 0.0, lastActive = null),
            session(id = "local", startedAt = 0.0, lastActive = null),
            session(id = "server-last", startedAt = 0.0, lastActive = null),
        )
        val identity = sessionIdentity(origin, rows[1])

        assertEquals(
            listOf("local", "server-first", "server-last"),
            orderSessions(
                sessions = rows,
                origin = origin,
                ordering = SessionOrdering.SERVER_ORDER,
                overlays = mapOf(
                    identity to PendingLocalActivity(
                        bumpSeconds = 100.0,
                        operationId = 1,
                        delivery = LocalActivityDelivery.UNCERTAIN,
                    ),
                ),
            ).map(StoredSession::id),
        )
    }

    @Test
    fun deduplicatesOnlyWithinOriginAndProfile() {
        val rows = listOf(
            session(id = "same", profile = "default", title = "first"),
            session(id = "same", profile = "default", title = "duplicate"),
            session(id = "same", profile = "work", title = "other profile"),
        )

        assertEquals(
            listOf("first", "other profile"),
            deduplicateSessions(rows, origin = "https://hermes.test")
                .map(StoredSession::title),
        )
    }

    @Test
    fun staleServerActivityKeepsAnOptimisticOverlayAndCatchUpClearsIt() {
        val origin = "https://hermes.test"
        val row = session(id = "same", lastActive = 10.0)
        val identity = sessionIdentity(origin, row)
        val overlay = PendingLocalActivity(
            bumpSeconds = 100.0,
            operationId = 7,
            delivery = LocalActivityDelivery.PENDING,
        )

        val stale = reconcileSessionRows(
            previous = listOf(row),
            page = SessionListPage(
                sessions = listOf(row.copy(lastActive = 20.0)),
                ordering = SessionOrdering.AUTHORITATIVE_RECENCY,
            ),
            origin = origin,
            profileScope = "all",
            overlays = mapOf(identity to overlay),
        )
        assertTrue(identity !in stale.overlaysConfirmed)
        assertEquals(listOf("same"), orderSessions(stale.sessions, origin, overlays = mapOf(identity to overlay)).map(StoredSession::id))

        val caughtUp = reconcileSessionRows(
            previous = stale.sessions,
            page = SessionListPage(
                sessions = listOf(row.copy(lastActive = 100.0)),
                ordering = SessionOrdering.AUTHORITATIVE_RECENCY,
            ),
            origin = origin,
            profileScope = "all",
            overlays = mapOf(identity to overlay),
        )
        assertEquals(setOf(identity), caughtUp.overlaysConfirmed)
    }

    @Test
    fun reconciliationDropsOmittedRowsButRetainsPendingAndSyntheticRows() {
        val origin = "https://hermes.test"
        val pending = session(id = "pending", lastActive = 10.0)
        val synthetic = session(id = "synthetic", startedAt = 0.0, lastActive = null)
        val omitted = session(id = "omitted", lastActive = 20.0)
        val pendingIdentity = sessionIdentity(origin, pending)
        val syntheticIdentity = sessionIdentity(origin, synthetic)

        val result = reconcileSessionRows(
            previous = listOf(pending, synthetic, omitted),
            page = SessionListPage(
                sessions = listOf(pending.copy(title = "updated")),
                ordering = SessionOrdering.AUTHORITATIVE_RECENCY,
            ),
            origin = origin,
            profileScope = "all",
            overlays = mapOf(
                pendingIdentity to PendingLocalActivity(
                    bumpSeconds = 100.0,
                    operationId = 2,
                    delivery = LocalActivityDelivery.UNCERTAIN,
                ),
            ),
            retainedIdentities = setOf(syntheticIdentity),
        )

        assertEquals(listOf("pending", "synthetic"), result.sessions.map(StoredSession::id))
        assertEquals("updated", result.sessions.first().title)
    }

    private fun session(
        id: String,
        title: String = id,
        startedAt: Double = 1.0,
        lastActive: Double? = 1.0,
        profile: String = "default",
    ) = StoredSession(
        id = id,
        title = title,
        preview = "",
        startedAt = startedAt,
        lastActive = lastActive,
        messageCount = 0,
        source = "android",
        profile = profile,
    )
}
