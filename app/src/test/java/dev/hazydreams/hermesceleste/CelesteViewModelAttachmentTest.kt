package dev.hazydreams.hermesceleste

import android.content.ContentResolver
import android.net.Uri
import dev.hazydreams.hermesceleste.attachments.AttachmentCapabilityState
import dev.hazydreams.hermesceleste.attachments.AttachmentStagingStore
import dev.hazydreams.hermesceleste.attachments.AttachmentTransferState
import dev.hazydreams.hermesceleste.attachments.DraftOwner
import dev.hazydreams.hermesceleste.attachments.FileAttachment
import dev.hazydreams.hermesceleste.attachments.MAX_PENDING_ATTACHMENTS
import dev.hazydreams.hermesceleste.attachments.MAX_SUBMIT_RETRIES
import dev.hazydreams.hermesceleste.attachments.StagedAttachment
import dev.hazydreams.hermesceleste.connection.InMemoryConnectionStore
import dev.hazydreams.hermesceleste.network.AuthenticationMaterial
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
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertFalse
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
    fun removingDuringUploadCancelsTheTransactionAndDetachesAlreadyStagedReferences() = runTest {
        val gateway = AttachmentGateway().apply {
            blockSecondAttach = true
            secondAttachStarted = CompletableDeferred()
        }
        val store = FakeAttachmentStore()
        val viewModel = openConversation(
            gateway,
            store,
            reconnectDelayMillis = { _, _ -> 60_000L },
        )
        viewModel.updateDraft("Keep these images")
        viewModel.beginAttachmentPicker()
        viewModel.onAttachmentPickerResult(
            listOf(Uri.parse("content://image-1"), Uri.parse("content://image-2")),
        )
        advanceUntilIdle()
        viewModel.sendMessage()
        gateway.secondAttachStarted!!.await()

        gateway.disconnect("upload connection lost")
        assertEquals(TurnState.Reconnecting, viewModel.state.value.turnState)
        val removedId = viewModel.state.value.attachments[1].id
        viewModel.removeAttachment(removedId)
        advanceUntilIdle()

        assertFalse("prompt.submit" in gateway.methods)
        assertEquals(1, viewModel.state.value.attachments.size)
        assertEquals(null, viewModel.state.value.attachments.single().serverReference)
        assertEquals(AttachmentTransferState.Ready, viewModel.state.value.attachments.single().transfer)
        assertTrue(viewModel.state.value.messages.none { it.id?.startsWith("local-") == true })
        assertEquals(1, gateway.methods.count { it == "image.detach" })
    }

    @Test
    fun contextSwitchCapturesTransactionReferencesForOwnerScopedCleanup() = runTest {
        val gateway = AttachmentGateway().apply {
            blockSecondAttach = true
            secondAttachStarted = CompletableDeferred()
        }
        val dashboard = FakeDashboard(gateway)
        val store = FakeAttachmentStore()
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

        viewModel.updateDraft("Keep these images")
        viewModel.beginAttachmentPicker()
        viewModel.onAttachmentPickerResult(
            listOf(Uri.parse("content://image-1"), Uri.parse("content://image-2")),
        )
        advanceUntilIdle()
        viewModel.sendMessage()
        gateway.secondAttachStarted!!.await()

        viewModel.leaveConversation()
        advanceUntilIdle()
        viewModel.openSession(dashboard.session)
        advanceUntilIdle()

        assertEquals(1, gateway.detachRequests.size)
        assertEquals("stored-1", gateway.detachRequests.single()["session_id"]?.toString()?.trim('"'))
        assertEquals("/hermes/images/upload.png", gateway.detachRequests.single()["path"]?.toString()?.trim('"'))
    }

    @Test
    fun timeoutDuringSubmitReconcilesTheStoredSessionWithoutResending() = runTest {
        val gateway = AttachmentGateway().apply {
            promptFailure = GatewayRequestTimeout("prompt.submit", IOException("timed out"))
            disconnectOnPromptFailure = true
        }
        val store = FakeAttachmentStore()
        val viewModel = openConversation(gateway, store)
        gateway.resumeMessages = """[{"id":"authoritative-user","role":"user","text":"Keep this caption"}]"""

        viewModel.updateDraft("Keep this caption")
        viewModel.sendMessage()
        advanceUntilIdle()

        assertEquals(1, gateway.methods.count { it == "prompt.submit" })
        assertTrue(gateway.connectCount >= 2)
        assertEquals("", viewModel.state.value.draft)
        assertTrue(viewModel.state.value.messages.any { it.id == "authoritative-user" })
        assertEquals(TurnState.Idle, viewModel.state.value.turnState)
    }

    @Test
    fun removalIsBlockedAfterPromptSubmitBeginsUntilTheSendIsReconciled() = runTest {
        val gateway = AttachmentGateway().apply {
            blockPrompt = true
            promptStarted = CompletableDeferred()
        }
        val viewModel = openConversation(
            gateway,
            FakeAttachmentStore(),
            reconnectDelayMillis = { _, _ -> 60_000L },
        )
        viewModel.updateDraft("Keep this image")
        viewModel.beginAttachmentPicker()
        viewModel.onAttachmentPickerResult(listOf(Uri.parse("content://image-1")))
        advanceUntilIdle()
        viewModel.sendMessage()
        gateway.promptStarted!!.await()

        gateway.disconnect("lost after submit started")
        val attachmentId = viewModel.state.value.attachments.single().id
        viewModel.removeAttachment(attachmentId)

        assertEquals("Reconcile the send status before removing this image.", viewModel.state.value.attachmentNotice)
        assertEquals(1, viewModel.state.value.attachments.size)
        assertEquals(0, gateway.methods.count { it == "image.detach" })

        gateway.releasePrompt.complete(Unit)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.attachments.isEmpty())
    }

    @Test
    fun staleUploadFailureRemovesItsOptimisticRowAfterTheDraftChanges() = runTest {
        val gateway = AttachmentGateway().apply {
            blockSecondAttach = true
            secondAttachStarted = CompletableDeferred()
        }
        val viewModel = openConversation(gateway, FakeAttachmentStore())
        viewModel.updateDraft("Old caption")
        viewModel.beginAttachmentPicker()
        viewModel.onAttachmentPickerResult(
            listOf(Uri.parse("content://image-1"), Uri.parse("content://image-2")),
        )
        advanceUntilIdle()
        viewModel.sendMessage()
        gateway.secondAttachStarted!!.await()

        viewModel.updateDraft("New caption")
        gateway.attachFailure = GatewayRpcException(4018, "image too large")
        gateway.releaseSecondAttach.complete(Unit)
        advanceUntilIdle()

        assertEquals("New caption", viewModel.state.value.draft)
        assertTrue(viewModel.state.value.messages.none { it.id?.startsWith("local-") == true })
    }

    @Test
    fun explicitRetriesAfterAnAbsentUnknownSubmitAreBounded() = runTest {
        val gateway = AttachmentGateway().apply {
            promptFailure = GatewayRequestTimeout("prompt.submit", IOException("timed out"))
            resumeMessages = "[]"
        }
        val viewModel = openConversation(gateway, FakeAttachmentStore())
        viewModel.updateDraft("Retry this image")
        viewModel.beginAttachmentPicker()
        viewModel.onAttachmentPickerResult(listOf(Uri.parse("content://image-1")))
        advanceUntilIdle()

        viewModel.sendMessage()
        advanceUntilIdle()
        repeat(MAX_SUBMIT_RETRIES) {
            viewModel.sendMessage()
            advanceUntilIdle()
        }
        val submittedCount = gateway.methods.count { it == "prompt.submit" }

        viewModel.sendMessage()
        advanceUntilIdle()

        assertEquals(1 + MAX_SUBMIT_RETRIES, submittedCount)
        assertEquals(submittedCount, gateway.methods.count { it == "prompt.submit" })
        assertTrue(viewModel.state.value.attachmentNotice.orEmpty().contains("Retry limit reached"))
    }

    @Test
    fun acceptedSendReportsLocalCleanupFailureInsteadOfSilentlyDroppingIt() = runTest {
        val gateway = AttachmentGateway()
        val store = FakeAttachmentStore().apply { deleteResult = false }
        val viewModel = openConversation(gateway, store)
        viewModel.updateDraft("Report cleanup")
        viewModel.beginAttachmentPicker()
        viewModel.onAttachmentPickerResult(listOf(Uri.parse("content://image-1")))
        advanceUntilIdle()
        viewModel.sendMessage()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.attachments.isEmpty())
        assertEquals("", viewModel.state.value.draft)
        assertTrue(viewModel.state.value.attachmentNotice.orEmpty().contains("could not remove"))
    }

    private suspend fun openConversation(
        gateway: AttachmentGateway,
        store: FakeAttachmentStore,
        reconnectDelayMillis: (Int, Boolean) -> Long = { _, _ -> 0L },
    ): CelesteViewModel {
        val dashboard = FakeDashboard(gateway)
        val viewModel = CelesteViewModel(
            dashboard = dashboard,
            connectionStore = InMemoryConnectionStore(),
            attachmentStagingStore = store,
            reconnectDelayMillis = reconnectDelayMillis,
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
        var deleteResult = true

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

        override suspend fun delete(localFileId: String): Boolean = deleteResult
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
        var connectCount = 0
        var attachCallCount = 0
        var blockSecondAttach = false
        var secondAttachStarted: CompletableDeferred<Unit>? = null
        val releaseSecondAttach = CompletableDeferred<Unit>()
        var blockPrompt = false
        var promptStarted: CompletableDeferred<Unit>? = null
        val releasePrompt = CompletableDeferred<Unit>()
        val detachRequests = mutableListOf<JsonObject>()
        var disconnectOnPromptFailure = false
        var attachFailure: Throwable? = null
        var promptFailure: Throwable? = null
        var promptText: String? = null
        var resumeMessages: String = "[]"

        override suspend fun connect() {
            connectCount += 1
            mutableState.value = GatewayConnectionState.Connected
        }
        override suspend fun request(method: String, params: JsonObject, timeoutMillis: Long): JsonElement {
            methods += method
            when (method) {
                "image.attach_bytes" -> {
                    attachCallCount += 1
                    if (attachCallCount == 2 && blockSecondAttach) {
                        secondAttachStarted?.complete(Unit)
                        releaseSecondAttach.await()
                    }
                    attachFailure?.let { throw it }
                }
                "prompt.submit" -> {
                    promptStarted?.complete(Unit)
                    if (blockPrompt) releasePrompt.await()
                    promptFailure?.let {
                        if (disconnectOnPromptFailure) {
                            mutableState.value = GatewayConnectionState.Disconnected("lost")
                        }
                        throw it
                    }
                    promptText = params["text"]?.toString()?.trim('"')
                }
                "image.detach" -> detachRequests += params
                "session.resume" -> return buildJsonObject {
                    put("session_id", "runtime-1")
                    put("resumed", "stored-1")
                    put("running", false)
                    put("messages", Json.parseToJsonElement(resumeMessages))
                }
            }
            return when (method) {
                "image.attach_bytes" -> buildJsonObject { put("path", "/hermes/images/upload.png"); put("bytes", 16) }
                "image.detach" -> buildJsonObject { put("detached", true) }
                "prompt.submit" -> buildJsonObject { put("status", "streaming") }
                else -> buildJsonObject {}
            }
        }
        override fun close() { mutableState.value = GatewayConnectionState.Closed }

        fun disconnect(reason: String) {
            mutableState.value = GatewayConnectionState.Disconnected(reason)
        }
    }
}
