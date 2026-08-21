package dev.hazydreams.hermesceleste.ui.sessions

import dev.hazydreams.hermesceleste.network.StoredSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionSectionsTest {
    @Test
    fun partitionsPinnedRecentAndScheduledWithoutDuplicates() {
        val sessions = listOf(
            session("pinned-chat", pinned = true),
            session("recent-chat"),
            session("scheduled-run", source = "cron"),
            session("pinned-run", source = "CRON", pinned = true),
            session("legacy-chat", pinned = null),
        )

        val sections = sessions.toSessionSections()

        assertEquals(listOf("pinned-chat", "pinned-run"), sections.pinned.map { it.id })
        assertEquals(listOf("recent-chat", "legacy-chat"), sections.recents.map { it.id })
        assertEquals(listOf("scheduled-run"), sections.scheduled.map { it.id })
        assertEquals(
            sessions.map { it.id }.toSet(),
            (sections.pinned + sections.recents + sections.scheduled).map { it.id }.toSet(),
        )
    }

    @Test
    fun compactMetadataUsesOnlyAuthoritativeAvailableFields() {
        assertEquals(
            "Scheduled  ·  hermes-4  ·  work",
            session("run", source = " cron ", model = " hermes-4 ", profile = "work")
                .compactMetadata(showProfile = true),
        )
        assertEquals(
            "hermes-4",
            session("chat", model = "hermes-4").compactMetadata(showProfile = false),
        )
        assertEquals(
            "hermes-4  ·  work",
            session("run", source = "cron", model = "hermes-4", profile = "work")
                .compactMetadata(showProfile = true, includeScheduledMarker = false),
        )
        assertNull(session("legacy").compactMetadata(showProfile = false))
    }

    private fun session(
        id: String,
        source: String = "desktop",
        profile: String = "default",
        model: String? = null,
        pinned: Boolean? = false,
    ) = StoredSession(
        id = id,
        title = id,
        preview = "",
        startedAt = 0.0,
        messageCount = 1,
        source = source,
        profile = profile,
        model = model,
        pinned = pinned,
    )
}
