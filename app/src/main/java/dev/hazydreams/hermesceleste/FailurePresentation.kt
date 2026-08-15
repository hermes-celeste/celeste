package dev.hazydreams.hermesceleste

import kotlinx.coroutines.CancellationException

/**
 * Shared DF-07 sanitization seam. This intentionally handles only the
 * cancellation/lifecycle leakage needed by DF-05; the broader failure
 * taxonomy remains a DF-07 responsibility.
 */
internal fun sanitizeFailure(
    error: Throwable?,
    fallback: String? = null,
): String? {
    if (error == null) return fallback

    var cause: Throwable? = error
    while (cause != null) {
        if (cause is CancellationException) return null
        cause = cause.cause
    }

    return sanitizeFailureMessage(error.message, fallback)
}

internal fun sanitizeFailureMessage(
    rawMessage: String?,
    fallback: String? = null,
): String? {
    val cleaned = rawMessage
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.take(240)
        ?.takeIf(String::isNotBlank)
        ?: return fallback
    val normalized = cleaned.lowercase()
    val isCancellationLeak = normalized.contains("standalonecoroutine") ||
        normalized.contains("jobcancellationexception") ||
        normalized.contains("cancellationexception") ||
        (normalized.contains("coroutine") && normalized.contains("cancel"))
    return if (isCancellationLeak) null else cleaned
}
