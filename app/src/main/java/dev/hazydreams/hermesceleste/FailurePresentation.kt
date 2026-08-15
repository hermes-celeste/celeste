package dev.hazydreams.hermesceleste

import dev.hazydreams.hermesceleste.network.AuthenticationRejected
import dev.hazydreams.hermesceleste.network.InvalidDashboardResponse
import dev.hazydreams.hermesceleste.network.RateLimited
import dev.hazydreams.hermesceleste.network.TransportUnavailable
import kotlinx.coroutines.CancellationException

/**
 * The only boundary allowed to turn failures into product copy. Raw exception
 * and server text is never returned; callers provide a fixed product fallback
 * or receive a fixed message for one of the typed dashboard failures.
 */
internal sealed interface FailurePresentation {
    data object Suppressed : FailurePresentation
    data class SafeMessage(val value: String) : FailurePresentation
}

internal fun presentFailure(
    error: Throwable?,
    fallback: String? = null,
): FailurePresentation {
    if (error == null) return safeProductMessage(fallback)
    if (hasCancellationCause(error)) return FailurePresentation.Suppressed

    val productFallback = fallback ?: when (error.rootCause()) {
        is AuthenticationRejected -> "Hermes rejected the dashboard credential. Sign in again."
        is RateLimited -> "Hermes is busy. Try again shortly."
        is TransportUnavailable -> "Could not reach Hermes."
        is InvalidDashboardResponse -> "Hermes returned an unexpected response."
        else -> null
    }
    return safeProductMessage(productFallback)
}

internal fun sanitizeFailure(
    error: Throwable?,
    fallback: String? = null,
): String? = (presentFailure(error, fallback) as? FailurePresentation.SafeMessage)?.value

/**
 * Redacts event, transport, and UI-bound text. A raw value is accepted only
 * when it is one of Celeste's fixed messages; otherwise the fixed fallback is
 * used. Cancellation-shaped text is always suppressed.
 */
internal fun sanitizeFailureMessage(
    rawMessage: String?,
    fallback: String? = null,
): String? {
    if (isCancellationText(rawMessage)) return null
    val normalized = normalizeFailureText(rawMessage)
    return when {
        normalized == null -> safeProductMessage(fallback)
        normalized in PRODUCT_FAILURE_MESSAGES -> normalized
        else -> safeProductMessage(fallback)
    }
}

private fun safeProductMessage(value: String?): FailurePresentation =
    normalizeFailureText(value)
        ?.takeIf { it in PRODUCT_FAILURE_MESSAGES }
        ?.let(FailurePresentation::SafeMessage)
        ?: FailurePresentation.Suppressed

private fun normalizeFailureText(value: String?): String? =
    value
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.takeIf(String::isNotBlank)

private fun hasCancellationCause(error: Throwable): Boolean {
    var cause: Throwable? = error
    while (cause != null) {
        if (cause is CancellationException) return true
        cause = cause.cause
    }
    return false
}

private fun Throwable.rootCause(): Throwable {
    var root = this
    while (root.cause != null) root = requireNotNull(root.cause)
    return root
}

private fun isCancellationText(rawMessage: String?): Boolean {
    val normalized = rawMessage
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.lowercase()
        ?: return false
    return normalized.contains("standalonecoroutine") ||
        normalized.contains("jobcancellationexception") ||
        normalized.contains("cancellationexception") ||
        (normalized.contains("coroutine") && normalized.contains("cancel"))
}

private val PRODUCT_FAILURE_MESSAGES = setOf(
    "Could not reach the Hermes dashboard.",
    "Could not load Hermes conversations.",
    "Connected, but Celeste could not remember this connection.",
    "Celeste could not remove the saved sign-in. Try Forget connection.",
    "Celeste could not remove the saved connection. Try again.",
    "Celeste could not read the saved connection. Sign in again.",
    "Could not reconnect to Hermes.",
    "Connected, but Celeste could not refresh the saved sign-in.",
    "Saved sign-in is no longer valid. Sign in again.",
    "Could not open that Hermes conversation.",
    "Could not create a Hermes conversation.",
    "Hermes could not send that message.",
    "Hermes could not stop that turn.",
    "Reconnecting to Hermes…",
    "Hermes could not finish that response.",
    "Hermes reported an error.",
    "Hermes rejected the dashboard credential. Sign in again.",
    "Hermes is busy. Try again shortly.",
    "Hermes returned an unexpected response.",
)
