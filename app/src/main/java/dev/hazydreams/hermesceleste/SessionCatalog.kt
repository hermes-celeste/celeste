package dev.hazydreams.hermesceleste

import dev.hazydreams.hermesceleste.network.StoredSession
import java.util.Locale

/**
 * The identity used by the conversation catalog. A runtime gateway id is
 * deliberately absent: it is only valid for one live attachment.
 */
internal data class SessionKey(
    val originKey: String,
    val profile: String,
    val durableId: String,
) {
    init {
        require(originKey.isNotBlank()) { "A session key needs an origin." }
        require(profile.isNotBlank()) { "A session key needs a profile." }
        require(durableId.isNotBlank()) { "A session key needs a durable session id." }
    }

    companion object {
        fun from(origin: String, profile: String, durableId: String): SessionKey? {
            val normalizedOrigin = normalizeOrigin(origin)
            val normalizedProfile = normalizeProfile(profile)
            val normalizedId = durableId.trim()
            if (normalizedOrigin.isBlank() || normalizedProfile.isBlank() || normalizedId.isBlank()) {
                return null
            }
            return SessionKey(normalizedOrigin, normalizedProfile, normalizedId)
        }
    }
}

internal data class SessionScope(
    val originKey: String,
    val profile: String,
) {
    init {
        require(originKey.isNotBlank()) { "A catalog scope needs an origin." }
        require(profile.isNotBlank()) { "A catalog scope needs a profile." }
    }

    companion object {
        const val ALL_PROFILES = "*"

        fun from(origin: String, profile: String): SessionScope? {
            val normalizedOrigin = normalizeOrigin(origin)
            val normalizedProfile = normalizeProfile(profile)
            if (normalizedOrigin.isBlank() || normalizedProfile.isBlank()) return null
            return SessionScope(normalizedOrigin, normalizedProfile)
        }

        fun allProfiles(origin: String): SessionScope? = from(origin, ALL_PROFILES)
    }

    fun accepts(key: SessionKey): Boolean =
        key.originKey == originKey && (profile == ALL_PROFILES || key.profile == profile)
}

/** Captures every generation that must still be current before a list publishes. */
internal data class SessionCatalogRequest(
    val scope: SessionScope,
    val originGeneration: Long,
    val profileGeneration: Long,
    val requestGeneration: Long,
    val connectionAttempt: Long,
)

internal enum class SessionCatalogStatus {
    NotReady,
    Loading,
    Ready,
    Refreshing,
    Empty,
    NoResults,
    Stale,
    Error,
    Reconnecting,
    Opening,
    ActionInFlight,
}

/**
 * In-memory authoritative projection state. It is intentionally not
 * serializable or persisted; Hermes remains the catalog and transcript owner.
 */
internal data class SessionCatalogState(
    val phase: SessionCatalogStatus = SessionCatalogStatus.NotReady,
    val scope: SessionScope? = null,
    val rows: List<StoredSession> = emptyList(),
    val errorMessage: String? = null,
    val query: String = "",
    val request: SessionCatalogRequest? = null,
) {
    val filteredRows: List<StoredSession>
        get() = searchLoadedSessions(rows, query)

    /** No-results is a presentation state over a still-authoritative window. */
    val status: SessionCatalogStatus
        get() = if (
            query.isNotBlank() &&
            filteredRows.isEmpty() &&
            phase in setOf(
                SessionCatalogStatus.Ready,
                SessionCatalogStatus.Refreshing,
                SessionCatalogStatus.Stale,
            )
        ) {
            SessionCatalogStatus.NoResults
        } else {
            phase
        }

    fun withQuery(value: String): SessionCatalogState = copy(query = value)
}

/**
 * Pure reducer for catalog transitions. ViewModel/network code supplies the
 * generation token; stale responses are rejected here as well as at the call
 * site so a future mutation or alternative caller cannot publish across scope.
 */
internal object SessionCatalogReducer {
    fun begin(
        state: SessionCatalogState,
        request: SessionCatalogRequest,
        refreshing: Boolean,
    ): SessionCatalogState {
        val keepRows = refreshing && state.scope == request.scope
        return state.copy(
            phase = if (refreshing) SessionCatalogStatus.Refreshing else SessionCatalogStatus.Loading,
            scope = request.scope,
            rows = if (keepRows) state.rows else emptyList(),
            errorMessage = null,
            request = request,
        )
    }

    fun reconnecting(
        state: SessionCatalogState,
        scope: SessionScope,
        keepRows: Boolean,
    ): SessionCatalogState = state.copy(
        phase = SessionCatalogStatus.Reconnecting,
        scope = scope,
        rows = if (keepRows && state.scope == scope) state.rows else emptyList(),
        errorMessage = null,
        request = null,
    )

    fun succeeded(
        state: SessionCatalogState,
        request: SessionCatalogRequest,
        rows: List<StoredSession>,
    ): SessionCatalogState {
        if (state.request != request || state.scope != request.scope) return state
        val filtered = filterAuthoritativeRows(request.scope, rows)
        return state.copy(
            phase = if (filtered.isEmpty()) SessionCatalogStatus.Empty else SessionCatalogStatus.Ready,
            rows = filtered,
            errorMessage = null,
            request = null,
        )
    }

    fun failed(
        state: SessionCatalogState,
        request: SessionCatalogRequest,
        message: String,
    ): SessionCatalogState {
        if (state.request != request || state.scope != request.scope) return state
        return state.copy(
            phase = if (state.rows.isEmpty()) SessionCatalogStatus.Error else SessionCatalogStatus.Stale,
            errorMessage = message,
            request = null,
        )
    }

    internal fun filterAuthoritativeRows(
        scope: SessionScope,
        rows: List<StoredSession>,
    ): List<StoredSession> {
        val seen = linkedSetOf<SessionKey>()
        return rows.filter { row ->
            val key = row.keyFor(scope.originKey) ?: return@filter false
            scope.accepts(key) && seen.add(key)
        }
    }
}

/** Only server-supplied alternate identities may be registered here. */
internal data class VerifiedSessionIdentity(
    val key: SessionKey,
    val aliases: Set<String> = emptySet(),
)

internal class SessionAliasIndex {
    private val aliasesByKey = linkedMapOf<SessionKey, Set<String>>()

    fun replace(scope: SessionScope, identities: Iterable<VerifiedSessionIdentity>) {
        aliasesByKey.keys.removeAll { key -> scope.accepts(key) }
        identities.forEach { identity ->
            if (scope.accepts(identity.key)) {
                aliasesByKey[identity.key] = identity.aliases
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .toSet()
            }
        }
    }

    fun resolve(scope: SessionScope, value: String): SessionKey? {
        val candidate = value.trim()
        if (candidate.isBlank()) return null
        return aliasesByKey.entries.firstOrNull { (key, aliases) ->
            scope.accepts(key) &&
                (key.durableId == candidate || candidate in aliases)
        }?.key
    }

    fun clear() = aliasesByKey.clear()
}

internal fun StoredSession.keyFor(origin: String): SessionKey? =
    SessionKey.from(origin, profile, id)

internal fun normalizeProfile(value: String): String =
    value.trim().lowercase(Locale.ROOT)

internal fun normalizeOrigin(value: String): String =
    value.trim().trimEnd('/').lowercase(Locale.ROOT)

internal fun searchLoadedSessions(
    rows: List<StoredSession>,
    query: String,
): List<StoredSession> {
    val needle = query.trim().lowercase(Locale.ROOT)
    if (needle.isBlank()) return rows
    return rows.filter { row ->
        sequenceOf(row.title, row.preview, row.id, row.profile, row.source)
            .any { it.lowercase(Locale.ROOT).contains(needle) }
    }
}
