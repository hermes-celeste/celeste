package dev.hazydreams.hermesceleste

import java.io.IOException

import dev.hazydreams.hermesceleste.presentation.ASSISTANT_NAME_STORE_VERSION
import dev.hazydreams.hermesceleste.presentation.AssistantNameDiagnostic
import dev.hazydreams.hermesceleste.presentation.AssistantNameDiagnostics
import dev.hazydreams.hermesceleste.presentation.AssistantNameKey
import dev.hazydreams.hermesceleste.presentation.AssistantNameRecord
import dev.hazydreams.hermesceleste.presentation.AssistantNameRecordCommitter
import dev.hazydreams.hermesceleste.presentation.commitAssistantNameRecords
import dev.hazydreams.hermesceleste.presentation.decodeAssistantNameRecords
import dev.hazydreams.hermesceleste.presentation.InMemoryAssistantNameStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun failedAtomicCommitKeepsTheLastCommittedPayloadAndRetryReplacesItAsAWhole() {
        val oldPayload = "[old-record]"
        var persistedPayload: String? = oldPayload
        var failCommit = true
        val committer = AssistantNameRecordCommitter { encoded ->
            if (failCommit) {
                false
            } else {
                persistedPayload = encoded
                true
            }
        }
        val records = listOf(
            AssistantNameRecord(
                version = ASSISTANT_NAME_STORE_VERSION,
                origin = "https://gateway.example",
                profile = "default",
                name = "Nova",
            ),
        )

        val failure = runCatching {
            commitAssistantNameRecords(
                records = records,
                json = Json { ignoreUnknownKeys = false },
                committer = committer,
            )
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals(oldPayload, persistedPayload)

        failCommit = false
        commitAssistantNameRecords(
            records = records,
            json = Json { ignoreUnknownKeys = false },
            committer = committer,
        )
        val committed = decodeAssistantNameRecords(
            encoded = requireNotNull(persistedPayload),
            json = Json { ignoreUnknownKeys = false },
            diagnostics = AssistantNameDiagnostics { },
        )
        assertEquals(listOf("Nova"), committed.map { it.name })
    }

    @Test
    fun malformedAndUnsupportedRecordsUseHermesAndEmitRedactedDiagnostics() {
        val diagnostics = mutableListOf<AssistantNameDiagnostic>()
        val decoded = decodeAssistantNameRecords(
            encoded = """
                [
                  {"version":$ASSISTANT_NAME_STORE_VERSION,"origin":"https://gateway.example","profile":"default","name":"Juno"},
                  {"version":99,"origin":"https://gateway.example","profile":"work","name":"Nova"},
                  {"version":$ASSISTANT_NAME_STORE_VERSION,"origin":"https://gateway.example","profile":"default"}
                ]
            """.trimIndent(),
            json = Json { ignoreUnknownKeys = false },
            diagnostics = AssistantNameDiagnostics { diagnostic -> diagnostics += diagnostic },
        )
        decodeAssistantNameRecords(
            encoded = "not-json /private/Juno",
            json = Json { ignoreUnknownKeys = false },
            diagnostics = AssistantNameDiagnostics { diagnostic -> diagnostics += diagnostic },
        )

        assertEquals(1, decoded.size)
        assertEquals("Juno", decoded.single().name)
        assertTrue(diagnostics.contains(AssistantNameDiagnostic.UnsupportedVersion))
        assertTrue(diagnostics.contains(AssistantNameDiagnostic.MalformedRecord))
        assertTrue(diagnostics.contains(AssistantNameDiagnostic.MalformedPayload))
        assertTrue(diagnostics.none { it.code.contains("Juno") })
        assertTrue(diagnostics.none { it.code.contains("private") })
    }
}
