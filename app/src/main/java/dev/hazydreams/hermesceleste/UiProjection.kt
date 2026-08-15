package dev.hazydreams.hermesceleste

import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

/**
 * Product-facing recovery categories. Compose receives this typed projection,
 * never exception or server-provided error text.
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
    PreferencePersistence,
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
    val recovery: UiRecoveryAction,
    val scope: UiNoticeScope,
) {
    /** Stable, catalogued product copy. Raw server/exception text never enters here. */
    val message: String
        get() = when (category) {
            UiNoticeCategory.AuthenticationRequired -> "Your Hermes sign-in has expired. Sign in again."
            UiNoticeCategory.RateLimited -> "Hermes is busy right now. Try again shortly."
            UiNoticeCategory.Reconnecting -> "Reconnecting to Hermes…"
            UiNoticeCategory.TransportUnavailable -> "Hermes is unavailable right now. Try again."
            UiNoticeCategory.InvalidResponse -> "Hermes returned an unexpected response. Try again."
            UiNoticeCategory.ServerTurnFailure -> "Hermes couldn’t finish that response."
            UiNoticeCategory.GenericTurnFailure -> "Hermes reported an error. Try again."
            UiNoticeCategory.Persistence -> "Connected, but Celeste could not remember this connection."
            UiNoticeCategory.PreferencePersistence -> "Couldn’t save this display preference. Try again."
        }

    val recoveryLabel: String?
        get() = when (recovery) {
            UiRecoveryAction.None -> null
            UiRecoveryAction.Retry -> "Retry"
            UiRecoveryAction.SignIn -> "Sign in"
        }

    companion object {
        fun authentication(scope: UiNoticeScope = UiNoticeScope.Connection) = UiNotice(
            category = UiNoticeCategory.AuthenticationRequired,
            recovery = UiRecoveryAction.SignIn,
            scope = scope,
        )

        fun rateLimited(scope: UiNoticeScope = UiNoticeScope.Connection) = UiNotice(
            category = UiNoticeCategory.RateLimited,
            recovery = UiRecoveryAction.Retry,
            scope = scope,
        )

        fun reconnecting(scope: UiNoticeScope = UiNoticeScope.Session) = UiNotice(
            category = UiNoticeCategory.Reconnecting,
            recovery = UiRecoveryAction.None,
            scope = scope,
        )

        fun unavailable(scope: UiNoticeScope = UiNoticeScope.Session) = UiNotice(
            category = UiNoticeCategory.TransportUnavailable,
            recovery = UiRecoveryAction.Retry,
            scope = scope,
        )

        fun invalidResponse(scope: UiNoticeScope = UiNoticeScope.Connection) = UiNotice(
            category = UiNoticeCategory.InvalidResponse,
            recovery = UiRecoveryAction.Retry,
            scope = scope,
        )

        fun serverTurnFailure() = UiNotice(
            category = UiNoticeCategory.ServerTurnFailure,
            recovery = UiRecoveryAction.Retry,
            scope = UiNoticeScope.Turn,
        )

        fun genericTurnFailure() = UiNotice(
            category = UiNoticeCategory.GenericTurnFailure,
            recovery = UiRecoveryAction.Retry,
            scope = UiNoticeScope.Turn,
        )

        fun persistence() = UiNotice(
            category = UiNoticeCategory.Persistence,
            recovery = UiRecoveryAction.Retry,
            scope = UiNoticeScope.Connection,
        )

        fun preferencePersistence() = UiNotice(
            category = UiNoticeCategory.PreferencePersistence,
            recovery = UiRecoveryAction.Retry,
            scope = UiNoticeScope.Connection,
        )
    }
}

/** Project any failure into fixed product copy and a bounded recovery action. */
internal fun projectUiNotice(error: Throwable, scope: UiNoticeScope): UiNotice? {
    if (isExpectedCancellation(error)) return null
    if (containsTimeoutCancellation(error)) return UiNotice.unavailable(scope)

    val typedError = findCause(error) {
        it is dev.hazydreams.hermesceleste.network.AuthenticationRejected ||
            it is dev.hazydreams.hermesceleste.network.RateLimited ||
            it is dev.hazydreams.hermesceleste.network.InvalidDashboardResponse ||
            it is dev.hazydreams.hermesceleste.network.TransportUnavailable ||
            it is dev.hazydreams.hermesceleste.network.GatewayRpcException
    }
    return when (typedError) {
        is dev.hazydreams.hermesceleste.network.AuthenticationRejected -> UiNotice.authentication(scope)
        is dev.hazydreams.hermesceleste.network.RateLimited -> UiNotice.rateLimited(scope)
        is dev.hazydreams.hermesceleste.network.InvalidDashboardResponse -> UiNotice.invalidResponse(scope)
        is dev.hazydreams.hermesceleste.network.TransportUnavailable -> UiNotice.unavailable(scope)
        is dev.hazydreams.hermesceleste.network.GatewayRpcException -> when (typedError.code) {
            401, 403 -> UiNotice.authentication(scope)
            429 -> UiNotice.rateLimited(scope)
            -32600, -32601, -32602, -32603 -> UiNotice.invalidResponse(scope)
            else -> if (scope == UiNoticeScope.Turn) {
                UiNotice.genericTurnFailure()
            } else {
                UiNotice.invalidResponse(scope)
            }
        }
        else -> if (scope == UiNoticeScope.Turn) {
            UiNotice.genericTurnFailure()
        } else {
            UiNotice.unavailable(scope)
        }
    }
}

/** Cancellation is structural control flow and must remain silent at the UI boundary. */
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
