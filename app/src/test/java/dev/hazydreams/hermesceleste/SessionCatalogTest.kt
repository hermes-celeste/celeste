package dev.hazydreams.hermesceleste

import dev.hazydreams.hermesceleste.network.StoredSession
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionCatalogTest {
    @Test
    fun keepsRecentSessionsAndEveryPinnedSession() {
        val now = 2_000_000.0
        val cutoff = now - 14 * 24 * 60 * 60
        val sessions = listOf(
            session("recent", lastActiveAt = now - 60),
            session("old-pinned", lastActiveAt = cutoff - 1, pinned = true),
            session("cutoff", lastActiveAt = cutoff),
            session("old", lastActiveAt = cutoff - 1),
        )

        assertEquals(
            listOf("recent", "old-pinned", "cutoff"),
            sessions.visibleSessionCatalog(now).map { it.id },
        )
    }

    private fun session(
        id: String,
        lastActiveAt: Double,
        pinned: Boolean = false,
    ) = StoredSession(
        id = id,
        title = id,
        preview = "",
        startedAt = lastActiveAt,
        messageCount = 1,
        source = "desktop",
        pinned = pinned,
        lastActiveAt = lastActiveAt,
    )
}