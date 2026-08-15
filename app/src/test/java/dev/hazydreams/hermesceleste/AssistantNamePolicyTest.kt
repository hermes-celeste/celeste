package dev.hazydreams.hermesceleste

import dev.hazydreams.hermesceleste.presentation.AssistantNameKey
import dev.hazydreams.hermesceleste.presentation.AssistantNamePolicy
import dev.hazydreams.hermesceleste.presentation.DEFAULT_ASSISTANT_NAME
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantNamePolicyTest {
    @Test
    fun blankInputResetsToTheSafeDefault() {
        val result = AssistantNamePolicy.validate(" \u2003 ")

        assertNull(result.normalized)
        assertNull(result.errorMessage)
        assertEquals("Hermes", DEFAULT_ASSISTANT_NAME)
    }

    @Test
    fun trimsUnicodeWhitespaceButPreservesCasingAndEmoji() {
        val result = AssistantNamePolicy.validate("\u2003 jUnO 🌙 \u2003")

        assertEquals("jUnO 🌙", result.normalized)
        assertNull(result.errorMessage)
    }

    @Test
    fun rejectsControlsAndUnicodeLineSeparatorsWithoutTruncating() {
        listOf("Juno\n", "Juno\u0000", "Juno\u2028", "Juno\u2029").forEach { input ->
            val result = AssistantNamePolicy.validate(input)
            assertNull(result.normalized)
            assertTrue(result.errorMessage?.isNotBlank() == true)
        }
    }

    @Test
    fun limitsUnicodeCodePointsRatherThanUtf16Units() {
        val valid = AssistantNamePolicy.validate("🌙".repeat(40))
        val invalid = AssistantNamePolicy.validate("🌙".repeat(41))

        assertEquals(40, valid.normalized?.codePointCount(0, valid.normalized!!.length))
        assertNull(invalid.normalized)
        assertTrue(invalid.errorMessage?.contains("40") == true)
    }

    @Test
    fun canonicalKeyNormalizesOriginPathAndProfile() {
        assertEquals(
            AssistantNameKey(origin = "https://gateway.example/hermes", profile = "default"),
            AssistantNameKey.from(" HTTPS://Gateway.Example/hermes/ ", " "),
        )
        assertEquals(
            AssistantNameKey(origin = "https://gateway.example/other", profile = "work"),
            AssistantNameKey.from("https://gateway.example/other", " work "),
        )
    }

    @Test
    fun invalidGatewayContextDoesNotProduceAStoreKey() {
        assertNull(AssistantNameKey.from("not a dashboard", "default"))
        assertNull(AssistantNameKey.from(null, "default"))
        assertFalse(AssistantNameKey.from("https://gateway.example", null) != null)
    }
}
