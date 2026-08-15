package dev.hazydreams.hermesceleste

import dev.hazydreams.hermesceleste.presentation.AssistantNameKey
import dev.hazydreams.hermesceleste.presentation.InMemoryAssistantNameStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class AssistantNameStoreTest {
    @Test
    fun missingAndResetEntriesUseTheHermesFallback() = runTest {
        val store = InMemoryAssistantNameStore()
        val origin = "https://gateway.example/hermes"

        assertNull(store.read(origin, "default"))
        store.write(origin, "default", "Juno")
        assertEquals("Juno", store.read(origin, "default"))
        store.write(origin, "default", null)
        assertNull(store.read(origin, "default"))
    }

    @Test
    fun entriesIsolateOriginsPathPrefixesAndProfilesAcrossStoreInstances() = runTest {
        val backing = linkedMapOf<AssistantNameKey, String>()
        val first = InMemoryAssistantNameStore(backing)
        first.write("https://gateway.example/hermes", "default", "Juno")
        first.write("https://gateway.example/hermes", "work", "Nova")
        first.write("https://gateway.example/other", "default", "Atlas")

        val recreated = InMemoryAssistantNameStore(backing)
        assertEquals("Juno", recreated.read("https://gateway.example/hermes/", "default"))
        assertEquals("Nova", recreated.read("https://gateway.example/hermes", "work"))
        assertEquals("Atlas", recreated.read("https://gateway.example/other", "default"))

        recreated.clearOrigin("https://gateway.example/hermes/")

        assertNull(recreated.read("https://gateway.example/hermes", "default"))
        assertNull(recreated.read("https://gateway.example/hermes", "work"))
        assertEquals("Atlas", recreated.read("https://gateway.example/other", "default"))
    }

    @Test
    fun invalidWritesAreRejectedAndNeverSilentlyTruncated() = runTest {
        val store = InMemoryAssistantNameStore()

        try {
            store.write("https://gateway.example", "default", "Juno\n")
            fail("Expected control characters to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
        try {
            store.write("https://gateway.example", "default", "🌙".repeat(41))
            fail("Expected overlong names to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
        assertNull(store.read("https://gateway.example", "default"))
    }
}
