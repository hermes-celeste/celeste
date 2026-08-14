package dev.hazydreams.hermesceleste.connection

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionStoreTest {
    @Test
    fun bootstrapRestoresOnlyCompleteEnabledConnections() {
        assertEquals(ConnectionBootstrapDecision.ManualSetup, connectionBootstrapDecision(null))

        val open = SavedConnectionDescriptor(
            baseUrl = "https://hermes.example.net",
            authMode = SavedAuthMode.Open,
            expectsSecret = false,
        )
        assertTrue(connectionBootstrapDecision(StoredConnection(open, null)) is ConnectionBootstrapDecision.Restore)

        val password = SavedConnectionDescriptor(
            baseUrl = "https://hermes.example.net",
            authMode = SavedAuthMode.ProviderSession,
            provider = "password",
            username = "celeste",
            expectsSecret = true,
        )
        assertTrue(
            connectionBootstrapDecision(
                StoredConnection(password, ReusableSecret("synthetic-session-cookies")),
            ) is ConnectionBootstrapDecision.Restore,
        )
        assertTrue(
            connectionBootstrapDecision(StoredConnection(password, null)) is
                ConnectionBootstrapDecision.Prefill,
        )
        assertTrue(
            connectionBootstrapDecision(
                StoredConnection(password.copy(autoLoginEnabled = false), ReusableSecret("still-encrypted")),
            ) is ConnectionBootstrapDecision.Prefill,
        )
    }

    @Test
    fun inMemoryStoreSeparatesSignOutFromForget() = runTest {
        val descriptor = SavedConnectionDescriptor(
            baseUrl = "https://hermes.example.net/prefix",
            authMode = SavedAuthMode.StaticToken,
            expectsSecret = true,
        )
        val store = InMemoryConnectionStore()

        store.replace(descriptor, ReusableSecret("synthetic-token"))
        assertEquals(descriptor, store.load()?.descriptor)
        assertTrue(store.load()?.secret?.value == "synthetic-token")

        store.clearSecret()
        val signedOut = store.load()
        assertEquals(descriptor.baseUrl, signedOut?.descriptor?.baseUrl)
        assertFalse(signedOut?.descriptor?.autoLoginEnabled ?: true)
        assertNull(signedOut?.secret)

        store.forget()
        assertNull(store.load())
    }


    @Test
    fun reusableSecretNeverPrintsItsValue() {
        val secret = ReusableSecret("do-not-print")

        assertEquals("[REDACTED]", secret.toString())
        assertTrue(StoredConnection(
            descriptor = SavedConnectionDescriptor(
                baseUrl = "https://hermes.example.net",
                authMode = SavedAuthMode.StaticToken,
                expectsSecret = true,
            ),
            secret = secret,
        ).toString().contains("[REDACTED]"))
    }

    @Test
    fun encryptedMaterialIsBoundToTheApplicationEndpointPathAndAuthMode() {
        val descriptor = SavedConnectionDescriptor(
            baseUrl = "https://hermes.example.net/dashboard",
            authMode = SavedAuthMode.StaticToken,
            expectsSecret = true,
        )
        val original = AndroidConnectionStore.additionalData("dev.hazydreams.hermesceleste", descriptor)

        val changedPath = AndroidConnectionStore.additionalData(
            "dev.hazydreams.hermesceleste",
            descriptor.copy(baseUrl = "https://hermes.example.net/other"),
        )
        val changedApplication = AndroidConnectionStore.additionalData(
            "dev.hazydreams.other",
            descriptor,
        )
        val changedAuthMode = AndroidConnectionStore.additionalData(
            "dev.hazydreams.hermesceleste",
            descriptor.copy(authMode = SavedAuthMode.Open, expectsSecret = false),
        )

        assertFalse(original.contentEquals(changedPath))
        assertFalse(original.contentEquals(changedApplication))
        assertFalse(original.contentEquals(changedAuthMode))
    }
}
