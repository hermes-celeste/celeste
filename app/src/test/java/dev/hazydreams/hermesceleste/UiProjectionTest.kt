package dev.hazydreams.hermesceleste

import java.util.concurrent.CancellationException
import dev.hazydreams.hermesceleste.network.AuthenticationRejected
import dev.hazydreams.hermesceleste.network.GatewayRpcException
import dev.hazydreams.hermesceleste.network.InvalidDashboardResponse
import dev.hazydreams.hermesceleste.network.RateLimited
import dev.hazydreams.hermesceleste.network.TransportUnavailable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class UiProjectionTest {
    @Test
    fun projectsFailuresToFixedCopyWithoutCauseOrServerText() {
        val privateText = "private prompt https://internal.example/path /srv/secret"
        val cases = listOf(
            AuthenticationRejected(privateText) to UiNoticeCategory.AuthenticationRequired,
            RateLimited(privateText) to UiNoticeCategory.RateLimited,
            TransportUnavailable(privateText) to UiNoticeCategory.TransportUnavailable,
            InvalidDashboardResponse(privateText) to UiNoticeCategory.InvalidResponse,
            GatewayRpcException(500, privateText) to UiNoticeCategory.GenericTurnFailure,
        )

        cases.forEach { (error, category) ->
            val notice = requireNotNull(projectUiNotice(error, UiNoticeScope.Turn))
            assertEquals(category, notice.category)
            assertFalse(notice.message.contains(privateText))
            assertFalse(notice.message.contains("internal.example"))
            assertFalse(notice.message.contains("/srv/secret"))
        }
    }

    @Test
    fun wrappedCancellationIsSilentWhileTimeoutIsRecoverable() {
        val cancellation = CancellationException("private cancellation detail")
        val wrapped = IllegalStateException("legacy wrapper", cancellation)

        assertNull(projectUiNotice(cancellation, UiNoticeScope.Turn))
        assertNull(projectUiNotice(wrapped, UiNoticeScope.Connection))
    }
}
