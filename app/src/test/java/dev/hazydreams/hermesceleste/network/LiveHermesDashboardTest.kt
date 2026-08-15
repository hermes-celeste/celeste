package dev.hazydreams.hermesceleste.network

import kotlinx.coroutines.runBlocking
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
    fun listsARealDashboardThroughPrimaryRestOrCompatibilityContract() = runBlocking {
        val url = System.getenv("HERMES_CELESTE_LIVE_URL").orEmpty()
        assumeTrue("HERMES_CELESTE_LIVE_URL is not set", url.isNotBlank())
        val token = System.getenv("HERMES_CELESTE_LIVE_TOKEN").orEmpty()
        val client = DashboardClient()
        val probe = client.probe(url)
        assumeTrue(
            "HERMES_CELESTE_LIVE_TOKEN is required for this dashboard",
            !probe.authRequired || token.isNotBlank(),
        )
        val credential = token.takeIf(String::isNotBlank)
            ?.let(GatewayCredential::StaticToken)
            ?: GatewayCredential.None

        // listSessionPage owns the primary REST request and, when the server is
        // older, its permanent WebSocket session.list compatibility fallback.
        val page = client.listSessionPage(
            baseUrl = probe.baseUrl,
            credential = credential,
            profile = "all",
            limit = 10,
            offset = 0,
        )

        assertTrue("The page offset must be non-negative", page.offset >= 0)
        assertTrue("The page limit must be bounded", page.limit in 1..200)
        assertTrue("The page total cannot omit returned rows", page.total >= page.sessions.size)
        assertTrue("Every returned session needs a durable ID", page.sessions.all { it.id.isNotBlank() })
    }
}
