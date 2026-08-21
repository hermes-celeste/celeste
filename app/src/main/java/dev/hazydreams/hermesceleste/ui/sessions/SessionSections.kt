package dev.hazydreams.hermesceleste.ui.sessions

import dev.hazydreams.hermesceleste.network.StoredSession

internal data class SessionSections(
    val pinned: List<StoredSession>,
    val recents: List<StoredSession>,
    val scheduled: List<StoredSession>,
)

internal fun List<StoredSession>.toSessionSections(): SessionSections {
    val pinned = filter { it.pinned == true }
    val unpinned = filterNot { it.pinned == true }
    return SessionSections(
        pinned = pinned,
        recents = unpinned.filterNot(StoredSession::isScheduled),
        scheduled = unpinned.filter(StoredSession::isScheduled),
    )
}

internal fun StoredSession.isScheduled(): Boolean =
    source.trim().equals("cron", ignoreCase = true)

internal fun StoredSession.compactMetadata(
    showProfile: Boolean,
    includeScheduledMarker: Boolean = true,
): String? = buildList {
    if (includeScheduledMarker && isScheduled()) add("Scheduled")
    model?.trim()?.takeIf(String::isNotEmpty)?.let(::add)
    if (showProfile) profile.trim().takeIf(String::isNotEmpty)?.let(::add)
}.takeIf { it.isNotEmpty() }?.joinToString("  ·  ")
