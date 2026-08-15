package dev.hazydreams.hermesceleste

import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.Serializable

/**
 * Product-facing recovery categories. These values, rather than exception text,
 * are the contract between application state and Compose.
 */
internal enum class UiNoticeCategory {
    AuthenticationRequired,
    RateLimited,
    Reconnecting,
    TransportUnavailable,
    InvalidResponse,
    ServerTurnFailure,
    GenericTurnFailure,
    Persistence,
}

internal enum class UiNoticeScope {
    Connection,
    Session,
    Turn,
}

internal enum class UiRecoveryAction {
    None,
    Retry,
    SignIn,
}

internal data class UiNotice(
    val category: UiNoticeCategory,
    val copyKey: String,
    val recovery: UiRecoveryAction,
    val scope: UiNoticeScope,
) {
    /** Stable, catalogued product copy. No server or exception text enters here. */
    val message: String
        get() = copyFor(copyKey)

    val recoveryLabel: String?
        get() = when (recovery) {
            UiRecoveryAction.None -> null
            UiRecoveryAction.Retry -> "Retry"
            UiRecoveryAction.SignIn -> "Sign in"
        }

    companion object {
        fun authentication(scope: UiNoticeScope = UiNoticeScope.Connection) = UiNotice(
            category = UiNoticeCategory.AuthenticationRequired,
            copyKey = "authentication_required",
            recovery = UiRecoveryAction.SignIn,
            scope = scope,
        )

        fun rateLimited(scope: UiNoticeScope = UiNoticeScope.Connection) = UiNotice(
            category = UiNoticeCategory.RateLimited,
            copyKey = "rate_limited",
            recovery = UiRecoveryAction.Retry,
            scope = scope,
        )

        fun reconnecting(scope: UiNoticeScope = UiNoticeScope.Session) = UiNotice(
            category = UiNoticeCategory.Reconnecting,
            copyKey = "reconnecting",
            recovery = UiRecoveryAction.None,
            scope = scope,
        )

        fun unavailable(scope: UiNoticeScope = UiNoticeScope.Session) = UiNotice(
            category = UiNoticeCategory.TransportUnavailable,
            copyKey = "transport_unavailable",
            recovery = UiRecoveryAction.Retry,
            scope = scope,
        )

        fun invalidResponse(scope: UiNoticeScope = UiNoticeScope.Connection) = UiNotice(
            category = UiNoticeCategory.InvalidResponse,
            copyKey = "invalid_response",
            recovery = UiRecoveryAction.Retry,
            scope = scope,
        )

        fun serverTurnFailure() = UiNotice(
            category = UiNoticeCategory.ServerTurnFailure,
            copyKey = "server_turn_failure",
            recovery = UiRecoveryAction.Retry,
            scope = UiNoticeScope.Turn,
        )

        fun genericTurnFailure() = UiNotice(
            category = UiNoticeCategory.GenericTurnFailure,
            copyKey = "generic_turn_failure",
            recovery = UiRecoveryAction.Retry,
            scope = UiNoticeScope.Turn,
        )

        fun persistence() = UiNotice(
            category = UiNoticeCategory.Persistence,
            copyKey = "persistence_warning",
            recovery = UiRecoveryAction.None,
            scope = UiNoticeScope.Connection,
        )
    }
}

private fun copyFor(copyKey: String): String = when (copyKey) {
    "authentication_required" -> "Your Hermes sign-in has expired. Sign in again."
    "rate_limited" -> "Hermes is busy right now. Try again shortly."
    "reconnecting" -> "Reconnecting to Hermes…"
    "transport_unavailable" -> "Hermes is unavailable right now. Try again."
    "invalid_response" -> "Hermes returned an unexpected response. Try again."
    "server_turn_failure" -> "Hermes couldn’t finish that response."
    "generic_turn_failure" -> "Hermes reported an error. Try again."
    "persistence_warning" -> "Connected, but Celeste could not remember this connection."
    else -> "Hermes could not complete that action. Try again."
}

internal fun projectUiNotice(error: Throwable, scope: UiNoticeScope): UiNotice? {
    if (isExpectedCancellation(error)) return null
    if (containsTimeoutCancellation(error)) return UiNotice.unavailable(scope)
    return when (error) {
        is dev.hazydreams.hermesceleste.network.AuthenticationRejected -> UiNotice.authentication(scope)
        is dev.hazydreams.hermesceleste.network.RateLimited -> UiNotice.rateLimited(scope)
        is dev.hazydreams.hermesceleste.network.InvalidDashboardResponse -> UiNotice.invalidResponse(scope)
        is dev.hazydreams.hermesceleste.network.TransportUnavailable -> UiNotice.unavailable(scope)
        is dev.hazydreams.hermesceleste.network.GatewayRpcException -> when (error.code) {
            401, 403 -> UiNotice.authentication(scope)
            429 -> UiNotice.rateLimited(scope)
            -32600, -32601, -32602, -32603 -> UiNotice.invalidResponse(scope)
            else -> if (scope == UiNoticeScope.Turn) {
                UiNotice.genericTurnFailure()
            } else {
                UiNotice.invalidResponse(scope)
            }
        }
        else -> if (scope == UiNoticeScope.Turn) UiNotice.genericTurnFailure() else UiNotice.unavailable(scope)
    }
}
/**
 * Cancellation is structural control flow. A wrapped cancellation is also
 * silent at the UI boundary; only an intentionally-created timeout is a
 * current operation failure.
 */
internal fun isExpectedCancellation(error: Throwable): Boolean {
    if (containsTimeoutCancellation(error)) return false
    return findCause(error) { it is CancellationException } != null
}

private fun containsTimeoutCancellation(error: Throwable): Boolean =
    findCause(error) { it is TimeoutCancellationException } != null

private fun findCause(error: Throwable, predicate: (Throwable) -> Boolean): Throwable? {
    val seen: MutableSet<Throwable> =
        Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = error
    while (current != null && seen.add(current)) {
        if (predicate(current)) return current
        current = current.cause
    }
    return null
}

/** A content-free record suitable for a diagnostics sink or forced-redaction export. */
@Serializable
internal data class SanitizedDiagnostic(
    val category: String,
    val reasonCode: String,
    val operation: String,
    val exceptionClass: String? = null,
    val operationGeneration: Long? = null,
    val gatewayGeneration: Long? = null,
    val lifecycleGeneration: Long? = null,
    val retryCount: Int = 0,
)

internal fun interface DiagnosticsSink {
    fun record(diagnostic: SanitizedDiagnostic)
}

internal object NoopDiagnosticsSink : DiagnosticsSink {
    override fun record(diagnostic: SanitizedDiagnostic) = Unit
}

internal fun diagnosticReason(error: Throwable): String = when {
    isExpectedCancellation(error) -> "cancelled"
    containsTimeoutCancellation(error) -> "timeout"
    error is dev.hazydreams.hermesceleste.network.AuthenticationRejected -> "authentication_rejected"
    error is dev.hazydreams.hermesceleste.network.RateLimited -> "rate_limited"
    error is dev.hazydreams.hermesceleste.network.InvalidDashboardResponse -> "invalid_response"
    error is dev.hazydreams.hermesceleste.network.TransportUnavailable -> "transport_unavailable"
    error is dev.hazydreams.hermesceleste.network.GatewayRpcException -> "rpc_failure"
    error is TimeoutCancellationException -> "timeout"
    else -> "unexpected_failure"
}

internal fun diagnosticCategory(error: Throwable, scope: UiNoticeScope): String =
    projectUiNotice(error, scope)?.category?.name ?: "control_flow"
