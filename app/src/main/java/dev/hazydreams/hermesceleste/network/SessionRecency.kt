package dev.hazydreams.hermesceleste.network

import java.util.Locale

/** The source used to order one bounded dashboard session page. */
enum class SessionOrdering {
    /** The page carries enough numeric activity metadata for deterministic ordering. */
    AUTHORITATIVE_RECENCY,

    /** The server supplied an ordered compatibility page without numeric recency. */
    SERVER_ORDER,
}

data class SessionListError(
    val profile: String,
    val error: String,
)

data class SessionListPage(
    val sessions: List<StoredSession>,
    val total: Int = sessions.size,
    val offset: Int = 0,
    val limit: Int = 200,
    val errors: List<SessionListError> = emptyList(),
    val ordering: SessionOrdering = SessionOrdering.SERVER_ORDER,
)

internal enum class LocalActivityDelivery {
    PENDING,
    UNCERTAIN,
}

internal data class SessionIdentity(
    val origin: String,
    val profile: String,
    val storedSessionId: String,
)

internal data class PendingLocalActivity(
    val bumpSeconds: Double,
    val operationId: Long,
    val delivery: LocalActivityDelivery,
    val contextGeneration: Long = 0L,
)

internal data class ReconciledSessionRows(
    val sessions: List<StoredSession>,
    val overlaysConfirmed: Set<SessionIdentity>,
)

internal fun validEpochSeconds(value: Double?): Double? =
    value?.takeIf { it.isFinite() && it > 0.0 }

internal fun effectiveRemoteActivity(session: StoredSession): Double? =
    validEpochSeconds(session.lastActive) ?: validEpochSeconds(session.startedAt)

internal fun relativeActivityLabel(
    session: StoredSession,
    nowSeconds: Double,
): String? {
    val activity = effectiveRemoteActivity(session) ?: return null
    val elapsed = (nowSeconds - activity).coerceAtLeast(0.0).toLong()
    return when {
        elapsed < 60L -> "Active just now"
        elapsed < 3_600L -> String.format(Locale.getDefault(), "Active %d minutes ago", elapsed / 60L)
        elapsed < 86_400L -> String.format(Locale.getDefault(), "Active %d hours ago", elapsed / 3_600L)
        elapsed < 604_800L -> String.format(Locale.getDefault(), "Active %d days ago", elapsed / 86_400L)
        else -> String.format(Locale.getDefault(), "Active %d weeks ago", elapsed / 604_800L)
    }
}

internal fun normalizedSessionProfile(profile: String): String =
    profile.trim().ifEmpty { "default" }.lowercase(Locale.ROOT)

internal fun sessionIdentity(origin: String, session: StoredSession): SessionIdentity =
    SessionIdentity(
        origin = origin.trim().trimEnd('/'),
        profile = normalizedSessionProfile(session.profile),
        storedSessionId = session.id,
    )

/**
 * Keep the first authoritative row for a durable origin/profile/id identity.
 * The server payload is intentionally not copied into a second session store.
 */
internal fun deduplicateSessions(
    sessions: List<StoredSession>,
    origin: String = "",
): List<StoredSession> {
    val seen = LinkedHashSet<SessionIdentity>()
    return sessions.filter { seen.add(sessionIdentity(origin, it)) }
}

/**
 * Reconcile one bounded page with the previous projection and short-lived local
 * overlays. Server-owned row fields remain untouched; overlays affect only the
 * ordering projection and are cleared when Hermes confirms the bump.
 */
internal fun reconcileSessionRows(
    previous: List<StoredSession>,
    page: SessionListPage,
    origin: String,
    profileScope: String,
    overlays: Map<SessionIdentity, PendingLocalActivity>,
    retainedIdentities: Set<SessionIdentity> = emptySet(),
): ReconciledSessionRows {
    val scope = profileScope.trim().ifEmpty { "all" }
    val incoming = deduplicateSessions(
        page.sessions.filter { session ->
            scope.equals("all", ignoreCase = true) ||
                normalizedSessionProfile(session.profile).equals(scope, ignoreCase = true)
        },
        origin,
    )
    val incomingByIdentity = incoming.associateBy { sessionIdentity(origin, it) }
    val failedProfiles = page.errors
        .map { normalizedSessionProfile(it.profile) }
        .toSet()
    val previousRows = deduplicateSessions(previous, origin)
    val survivors = previousRows.filter { session ->
        val identity = sessionIdentity(origin, session)
        if (identity in incomingByIdentity) return@filter false
        val overlay = overlays[identity]
        val failedProfile = identity.profile in failedProfiles
        failedProfile || identity in retainedIdentities || overlay != null
    }
    val confirmed = overlays.keys.filterTo(linkedSetOf()) { identity ->
        val row = incomingByIdentity[identity]
        val serverActivity = validEpochSeconds(row?.lastActive)
        row != null && serverActivity != null && serverActivity >= overlays.getValue(identity).bumpSeconds
    }
    return ReconciledSessionRows(
        sessions = deduplicateSessions(incoming + survivors, origin),
        overlaysConfirmed = confirmed,
    )
}

/**
 * Deterministic Hermes-compatible ordering. No device clock is read here: the
 * caller supplies any local optimistic activity through [overlays].
 */
internal fun orderSessions(
    sessions: List<StoredSession>,
    origin: String = "",
    ordering: SessionOrdering = SessionOrdering.AUTHORITATIVE_RECENCY,
    overlays: Map<SessionIdentity, PendingLocalActivity> = emptyMap(),
): List<StoredSession> {
    val deduplicated = deduplicateSessions(sessions, origin)
    if (deduplicated.isEmpty()) return deduplicated

    data class IndexedRow(
        val index: Int,
        val session: StoredSession,
        val identity: SessionIdentity,
        val overlay: PendingLocalActivity?,
    )

    val indexed = deduplicated.mapIndexed { index, session ->
        val identity = sessionIdentity(origin, session)
        IndexedRow(index, session, identity, overlays[identity])
    }
    val relevantOverlays = indexed.mapNotNull { it.overlay }
    val hasNumericActivity = indexed.any { effectiveRemoteActivity(it.session) != null }

    // A compatibility page without numbers is already ordered by Hermes. A
    // local submit is still allowed to move its own row to the top immediately.
    if (ordering == SessionOrdering.SERVER_ORDER && relevantOverlays.isEmpty()) {
        return deduplicated
    }
    if (ordering == SessionOrdering.AUTHORITATIVE_RECENCY &&
        !hasNumericActivity && relevantOverlays.isEmpty()
    ) {
        return deduplicated
    }

    val sorted = if (ordering == SessionOrdering.SERVER_ORDER) {
        indexed.sortedWith { left, right ->
            val leftBump = validEpochSeconds(left.overlay?.bumpSeconds)
            val rightBump = validEpochSeconds(right.overlay?.bumpSeconds)
            when {
                leftBump != null && rightBump == null -> -1
                leftBump == null && rightBump != null -> 1
                leftBump != null && rightBump != null -> {
                    compareDescending(leftBump, rightBump).takeIf { it != 0 }
                        ?: left.index.compareTo(right.index)
                }
                else -> left.index.compareTo(right.index)
            }
        }
    } else {
        indexed.sortedWith { left, right ->
            val leftActivity = maxActivity(left.session, left.overlay)
            val rightActivity = maxActivity(right.session, right.overlay)
            if (leftActivity == null && rightActivity == null) {
                left.index.compareTo(right.index)
            } else {
                val leftStarted = validEpochSeconds(left.session.startedAt)
                val rightStarted = validEpochSeconds(right.session.startedAt)
                compareNullableDescending(leftActivity, rightActivity).takeIf { it != 0 }
                    ?: compareNullableDescending(leftStarted, rightStarted).takeIf { it != 0 }
                    ?: if (leftStarted != null && rightStarted != null) {
                        right.session.id.compareTo(left.session.id).takeIf { it != 0 }
                            ?: left.index.compareTo(right.index)
                    } else {
                        left.index.compareTo(right.index)
                    }
            }
        }
    }
    return sorted.map(IndexedRow::session)
}

private fun maxActivity(
    session: StoredSession,
    overlay: PendingLocalActivity?,
): Double? {
    val remote = effectiveRemoteActivity(session)
    val local = validEpochSeconds(overlay?.bumpSeconds)
    return when {
        remote == null -> local
        local == null -> remote
        else -> maxOf(remote, local)
    }
}

private fun compareNullableDescending(left: Double?, right: Double?): Int = when {
    left == null && right == null -> 0
    left == null -> 1
    right == null -> -1
    else -> compareDescending(left, right)
}

private fun compareDescending(left: Double, right: Double): Int =
    right.compareTo(left)
