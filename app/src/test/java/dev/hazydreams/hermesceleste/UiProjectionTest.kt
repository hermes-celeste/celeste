package dev.hazydreams.hermesceleste

import dev.hazydreams.hermesceleste.network.AuthenticationRejected
import dev.hazydreams.hermesceleste.network.GatewayRpcException
import dev.hazydreams.hermesceleste.network.InvalidDashboardResponse
import dev.hazydreams.hermesceleste.network.RateLimited
import dev.hazydreams.hermesceleste.network.TransportUnavailable
import java.util.concurrent.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiProjectionTest {
    @Test
    fun mapsCurrentFailuresToStableCopyAndRecoveryWithoutCauseText() {
        val forbiddenText = "StandaloneCoroutine was cancelled at https://private.example/path /srv/private prompt"
        val cases = listOf(
            AuthenticationRejected(forbiddenText) to UiNoticeCategory.AuthenticationRequired,
            RateLimited(forbiddenText) to UiNoticeCategory.RateLimited,
            TransportUnavailable(forbiddenText) to UiNoticeCategory.TransportUnavailable,
            InvalidDashboardResponse(forbiddenText) to UiNoticeCategory.InvalidResponse,
            GatewayRpcException(500, forbiddenText) to UiNoticeCategory.GenericTurnFailure,
        )

        cases.forEach { (error, category) ->
            val notice = requireNotNull(projectUiNotice(error, UiNoticeScope.Turn))
            assertEquals(category, notice.category)
            assertFalse(notice.message.contains(forbiddenText))
            assertFalse(notice.message.contains("StandaloneCoroutine"))
            assertFalse(notice.message.contains("private.example"))
            assertFalse(notice.message.contains("/srv/private"))
            assertFalse(notice.message.contains("prompt"))
        }
    }

    @Test
    fun cancellationAndWrappedCancellationAreSilentControlFlow() {
        val direct = CancellationException("StandaloneCoroutine was cancelled")
        val wrapped = IllegalStateException("legacy boundary", direct)

        assertNull(projectUiNotice(direct, UiNoticeScope.Turn))
        assertNull(projectUiNotice(wrapped, UiNoticeScope.Connection))
        assertEquals("cancelled", diagnosticReason(direct))
    }

    @Test
    fun diagnosticsSerializationIsContentFree() {
        val diagnostic = SanitizedDiagnostic(
            category = "TransportUnavailable",
            reasonCode = "transport_unavailable",
            operation = "open_session",
            exceptionClass = "IOException",
            operationGeneration = 7,
            gatewayGeneration = 3,
            lifecycleGeneration = 2,
            retryCount = 1,
        )
        val serialized = Json.encodeToString(diagnostic)

        assertTrue(serialized.contains("TransportUnavailable"))
        assertTrue(serialized.contains("open_session"))
        assertFalse(serialized.contains("https://private.example"))
        assertFalse(serialized.contains("prompt"))
        assertFalse(serialized.contains("StandaloneCoroutine"))
    }
}
