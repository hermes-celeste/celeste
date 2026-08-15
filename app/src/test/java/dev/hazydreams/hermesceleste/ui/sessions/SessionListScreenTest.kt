package dev.hazydreams.hermesceleste.ui.sessions

import dev.hazydreams.hermesceleste.network.DashboardProfile
import dev.hazydreams.hermesceleste.network.StoredSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun profileQualifiedKeysKeepTheFocusedAnchorStableWhenRowsReorder() {
        val focused = session(id = "focused", profile = "work")
        val oldRows = listOf(
            session(id = "first"),
            focused,
            session(id = "last"),
        )
        val reorderedRows = listOf(
            oldRows[2],
            oldRows[0],
            focused.copy(title = "Focused renamed"),
        )
        val focusedKey = sessionRowKey(focused)

        assertEquals(2, reorderedRows.map(::sessionRowKey).indexOf(focusedKey))
        assertEquals(focusedKey, sessionRowKey(reorderedRows[2]))
    }

    @Test
    fun reducedMotionDisablesPlacementTravelAtZeroOrInvalidAnimationScale() {
        assertFalse(shouldReduceMotion(1f))
        assertTrue(shouldReduceMotion(0f))
        assertTrue(shouldReduceMotion(Float.NaN))
    }

    @Test
    fun focusLossClearsOnlyTheRowThatWasTracked() {
        val focused = sessionRowKey(session("focused"))
        val other = sessionRowKey(session("other"))

        assertEquals(null, focusedRowKeyAfterFocusChange(focused, focused, isFocused = false))
        assertEquals(other, focusedRowKeyAfterFocusChange(other, focused, isFocused = false))
        assertEquals(focused, focusedRowKeyAfterFocusChange(other, focused, isFocused = true))
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
