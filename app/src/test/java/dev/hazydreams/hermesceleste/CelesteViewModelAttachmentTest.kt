package dev.hazydreams.hermesceleste

import android.content.ContentResolver
import android.net.Uri
import dev.hazydreams.hermesceleste.attachments.AttachmentCapabilityState
import dev.hazydreams.hermesceleste.attachments.AttachmentDraft
import dev.hazydreams.hermesceleste.attachments.AttachmentPreviewState
import dev.hazydreams.hermesceleste.attachments.AttachmentStagingStore
import dev.hazydreams.hermesceleste.attachments.AttachmentTransferState
import dev.hazydreams.hermesceleste.attachments.DraftOwner
import dev.hazydreams.hermesceleste.attachments.FileAttachment
import dev.hazydreams.hermesceleste.attachments.MAX_PENDING_ATTACHMENTS
import dev.hazydreams.hermesceleste.attachments.StagedAttachment
import dev.hazydreams.hermesceleste.connection.InMemoryConnectionStore
import dev.hazydreams.hermesceleste.network.AuthProvider
import dev.hazydreams.hermesceleste.network.AuthenticationMaterial
import dev.hazydreams.hermesceleste.network.ConversationMessage
import dev.hazydreams.hermesceleste.network.DashboardProfile
import dev.hazydreams.hermesceleste.network.DashboardProbeResult
import dev.hazydreams.hermesceleste.network.DashboardService
import dev.hazydreams.hermesceleste.network.GatewayConnection
import dev.hazydreams.hermesceleste.network.GatewayConnectionState
import dev.hazydreams.hermesceleste.network.GatewayCredential
import dev.hazydreams.hermesceleste.network.GatewayEvent
import dev.hazydreams.hermesceleste.network.GatewayRequestTimeout
import dev.hazydreams.hermesceleste.network.GatewayRpcException
import dev.hazydreams.hermesceleste.network.StoredSession
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CelesteViewModelAttachmentTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun stagesEveryAttachmentBeforeExactlyOnePromptAndClearsOnlyAfterAcceptance() = runTest {
        val gateway = AttachmentGateway()
        val store = FakeAttachmentStore()
        val viewModel = openConversation(gateway, store)

        viewModel.updateDraft("Describe these")
        viewModel.beginAttachmentPicker()
        viewModel.onAttachmentPickerResult((1..MAX_PENDING_ATTACHMENTS).map { Uri.parse("content://image-$it") })
        advanceUntilIdle()
        viewModel.sendMessage()
        advanceUntilIdle()

        assertEquals(
            listOf("image.attach_bytes", "image.attach_bytes", "image.attach_bytes", "image.attach_bytes", "prompt.submit"),
            gateway.methods,
        )
        assertEquals(
            (listOf("Describe these") + List(MAX_PENDING_ATTACHMENTS) { "@image:/hermes/images/upload.png" })
                .joinToString("\n"),
            gateway.promptText,
        )
        assertTrue(viewModel.state.value.attachments.isEmpty())
        assertEquals("", viewModel.state.value.draft)
    }

    @Test
    fun definitiveAttachmentFailureRetainsTheWholeDraftAndNeverSendsTextAlone() = runTest {
        val gateway = AttachmentGateway().apply { attachFailure = GatewayRpcException(-32601, "Method not found") }
        val store = FakeAttachmentStore()
        val viewModel = openConversation(gateway, store)

        viewModel.updateDraft("Keep this caption")
        viewModel.beginAttachmentPicker()
        viewModel.onAttachmentPickerResult(listOf(Uri.parse("content://image-1")))
        advanceUntilIdle()
        viewModel.sendMessage()
        advanceUntilIdle()

        assertTrue("prompt.submit" !in gateway.methods)
        assertEquals("Keep this caption", viewModel.state.value.draft)
        assertEquals(AttachmentTransferState.Failed, viewModel.state.value.attachments.single().transfer)
        assertEquals(AttachmentCapabilityState.Unsupported, viewModel.state.value.attachmentCapability)
    }

    @Test
    fun timeoutDuringUploadIsUnknownAndNeverFallsBackToTextOnlySend() = runTest {
        val gateway = AttachmentGateway().apply {
            attachFailure = GatewayRequestTimeout("image.attach_bytes", IOException("timed out"))
        }
        val store = FakeAttachmentStore()
        val viewModel = openConversation(gateway, store)

        viewModel.updateDraft("Keep the image")
        viewModel.beginAttachmentPicker()
        viewModel.onAttachmentPickerResult(listOf(Uri.parse("content://image-1")))
        advanceUntilIdle()
        viewModel.sendMessage()
        advanceUntilIdle()

        assertTrue("prompt.submit" !in gateway.methods)
        assertEquals("Keep the image", viewModel.state.value.draft)
        assertEquals(AttachmentTransferState.Unknown, viewModel.state.value.attachments.single().transfer)
        assertEquals(TurnState.Idle, viewModel.state.value.turnState)
    }

    @Test
    fun timeoutDuringSubmitReconcilesTheStoredSessionWithoutResending() = runTest {
        val gateway = AttachmentGateway().apply {
            promptFailure = GatewayRequestTimeout("prompt.submit", IOException("timed out"))
        }
        val store = FakeAttachmentStore()
        val viewModel = openConversation(gateway, store)
        gateway.resumeMessages = """[{"id":"authoritative-user","role":"user","text":"Keep this caption"}]"""

        viewModel.updateDraft("Keep this caption")
        viewModel.sendMessage()
        advanceUntilIdle()

        assertEquals(1, gateway.methods.count { it == "prompt.submit" })
        assertEquals("", viewModel.state.value.draft)
        assertTrue(viewModel.state.value.messages.any { it.id == "authoritative-user" })
        assertEquals(TurnState.Idle, viewModel.state.value.turnState)
    }

    private suspend fun openConversation(
        gateway: AttachmentGateway,
        store: FakeAttachmentStore,
    ): CelesteViewModel {
        val dashboard = FakeDashboard(gateway)
        val viewModel = CelesteViewModel(
            dashboard = dashboard,
            connectionStore = InMemoryConnectionStore(),
            attachmentStagingStore = store,
            reconnectDelayMillis = { _, _ -> 0L },
        )
        viewModel.updateDashboardUrl("http://hermes.test:9119")
        viewModel.findDashboard()
        viewModel.loadSessions()
        viewModel.openSession(dashboard.session)
        advanceUntilIdle()
        return viewModel
    }

    private class FakeAttachmentStore : AttachmentStagingStore {
        private val bytes = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
        )

        override suspend fun stageUri(
            resolver: ContentResolver?,
            uri: Uri,
            owner: DraftOwner,
            generation: Long,
        ): StagedAttachment = StagedAttachment(
            attachment = FileAttachment(
                localFileId = "local-${uri.lastPathSegment}",
                displayName = "${uri.lastPathSegment}.png",
                mimeType = "image/png",
                byteSize = bytes.size.toLong(),
                owner = owner,
                generation = generation,
            ),
            file = File("/private/test-only/${uri.lastPathSegment}.png"),
        )

        override suspend fun stage(
            input: InputStream,
            displayName: String?,
            declaredMimeType: String?,
            owner: DraftOwner,
            generation: Long,
        ): StagedAttachment = error("not used")

        override suspend fun readBytes(localFileId: String): ByteArray = bytes

        override suspend fun delete(localFileId: String) = Unit
    }

    private class FakeDashboard(private val gateway: AttachmentGateway) : DashboardService {
        val session = StoredSession("stored-1", "Test", "", 0.0, 0, "android")

        override suspend fun probe(rawBaseUrl: String) = DashboardProbeResult(rawBaseUrl, false, emptyList(), "test")
        override suspend fun passwordLogin(baseUrl: String, provider: String, username: String, password: String) = Unit
        override suspend fun listSessions(baseUrl: String, credential: GatewayCredential, limit: Int) = listOf(session)
        override suspend fun listProfiles(baseUrl: String, credential: GatewayCredential) = listOf(DashboardProfile("default", true))
        override fun exportAuthentication(baseUrl: String): AuthenticationMaterial? = null
        override fun createGateway(baseUrl: String, credential: GatewayCredential): GatewayConnection = gateway
    }

    private class AttachmentGateway : GatewayConnection {
        private val mutableState = MutableStateFlow<GatewayConnectionState>(GatewayConnectionState.Idle)
        override val state = mutableState
        override val events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 8)
        val methods = mutableListOf<String>()
        var attachFailure: Throwable? = null
        var promptFailure: Throwable? = null
        var promptText: String? = null
        var resumeMessages: String = "[]"

        override suspend fun connect() { mutableState.value = GatewayConnectionState.Connected }
        override suspend fun request(method: String, params: JsonObject, timeoutMillis: Long): JsonElement {
            methods += method
            when (method) {
                "image.attach_bytes" -> attachFailure?.let { throw it }
                "prompt.submit" -> {
                    promptFailure?.let { throw it }
                    promptText = params["text"]?.toString()?.trim('"')
                }
                "session.resume" -> return buildJsonObject {
                    put("session_id", "runtime-1")
                    put("resumed", "stored-1")
                    put("running", false)
                    put("messages", Json.parseToJsonElement(resumeMessages))
                }
            }
            return when (method) {
                "image.attach_bytes" -> buildJsonObject { put("path", "/hermes/images/upload.png"); put("bytes", 16) }
                "prompt.submit" -> buildJsonObject { put("status", "streaming") }
                else -> buildJsonObject {}
            }
        }
        override fun close() { mutableState.value = GatewayConnectionState.Closed }
    }
}
