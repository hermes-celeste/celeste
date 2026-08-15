package dev.hazydreams.hermesceleste.ui.sessions

import dev.hazydreams.hermesceleste.network.DashboardProfile
import dev.hazydreams.hermesceleste.network.StoredSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SessionListScreenTest {
    @Test
    fun rowKeysRemainStableAndProfileQualified() {
        val defaultRow = session(id = "same", profile = "default")
        val workRow = defaultRow.copy(profile = "work")

        assertEquals(sessionRowKey(defaultRow), sessionRowKey(defaultRow.copy(title = "renamed")))
        assertNotEquals(sessionRowKey(defaultRow), sessionRowKey(workRow))
    }

    @Test
    fun accessibilityLabelIncludesActivityProfileAndCount() {
        val label = sessionAccessibilityLabel(
            session = session(id = "conversation", title = "Build plan", lastActive = 3_600.0, messageCount = 4, profile = "work"),
            profiles = listOf(
                DashboardProfile(name = "default", isDefault = true),
                DashboardProfile(name = "work"),
            ),
            nowSeconds = 7_200.0,
        )

        assertEquals("Build plan. Active 1 hours ago. WORK profile. 4 messages", label)
    }

    @Test
    fun accessibilityLabelOmitsUnavailableActivityInsteadOfClaimingNow() {
        val label = sessionAccessibilityLabel(
            session = session(id = "unknown", title = "Unknown", lastActive = null, startedAt = 0.0),
            profiles = listOf(DashboardProfile(name = "default", isDefault = true)),
            nowSeconds = 10_000.0,
        )

        assertEquals("Unknown. 0 messages", label)
        assertFalse(label.contains("Active"))
    }

    private fun session(
        id: String,
        title: String = id,
        startedAt: Double = 1.0,
        lastActive: Double? = 1.0,
        messageCount: Int = 0,
        profile: String = "default",
    ) = StoredSession(
        id = id,
        title = title,
        preview = "",
        startedAt = startedAt,
        lastActive = lastActive,
        messageCount = messageCount,
        source = "android",
        profile = profile,
    )
}
