package dev.hazydreams.hermesceleste.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in contract test against a real Hermes dashboard and its real state.db.
 *
 * Run with HERMES_CELESTE_LIVE_URL. The optional token is read only from the
 * process environment and is never printed or persisted.
 */
class LiveHermesDashboardTest {
    @Test
    fun listsAndResumesARealStoredSession() = runBlocking {
        val url = System.getenv("HERMES_CELESTE_LIVE_URL").orEmpty()
        assumeTrue("HERMES_CELESTE_LIVE_URL is not set", url.isNotBlank())
        val token = System.getenv("HERMES_CELESTE_LIVE_TOKEN").orEmpty()
        val credential = if (token.isBlank()) {
            GatewayCredential.None
        } else {
            GatewayCredential.StaticToken(token)
        }
        val client = DashboardClient()

        val probe = client.probe(url)
        val sessions = client.listSessions(probe.baseUrl, credential, limit = 10).sessions

        assertTrue("The real Hermes state store returned no sessions", sessions.isNotEmpty())
        val selected = sessions.first()
        val resumed = client.resumeSession(probe.baseUrl, credential, selected.id)
        assertEquals(selected.id, resumed.storedSessionId)
        assertTrue(resumed.runtimeSessionId.isNotBlank())
    }
}
