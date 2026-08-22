package dev.hazydreams.hermesceleste.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.hazydreams.hermesceleste.network.StoredSession
import dev.hazydreams.hermesceleste.ui.CelesteAccent
import dev.hazydreams.hermesceleste.ui.CelesteError
import dev.hazydreams.hermesceleste.ui.CelestePanel
import dev.hazydreams.hermesceleste.ui.CelesteSurfaceSelected
import dev.hazydreams.hermesceleste.ui.CelesteTextMuted
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
internal fun SessionDrawerConversationList(
    sessions: List<StoredSession>,
    selectedSessionId: String?,
    enabled: Boolean,
    showProfile: Boolean,
    hasMoreSessions: Boolean,
    isLoadingMoreSessions: Boolean,
    sessionPageError: String?,
    searchQuery: String,
    searchResults: List<StoredSession>,
    isSearchingSessions: Boolean,
    sessionSearchError: String?,
    onSessionSelected: (StoredSession) -> Unit,
    onSessionPinnedChange: (StoredSession, Boolean) -> Unit,
    onSessionRename: (StoredSession, String, (String?) -> Unit) -> Unit,
    onLoadMoreSessions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pinnedExpanded by rememberSaveable { mutableStateOf(true) }
    var recentsExpanded by rememberSaveable { mutableStateOf(true) }
    var scheduledExpanded by rememberSaveable { mutableStateOf(true) }
    val sections = sessions.toSessionSections()
    val searchActive = searchQuery.isNotBlank()
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val currentLoadMore by rememberUpdatedState(onLoadMoreSessions)

    LaunchedEffect(
        listState,
        enabled,
        hasMoreSessions,
        isLoadingMoreSessions,
        sessionPageError,
        sessions.size,
        searchActive,
    ) {
        snapshotFlow {
            shouldRequestNextSessionPage(
                isScrollInProgress = listState.isScrollInProgress,
                lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1,
                totalItemCount = listState.layoutInfo.totalItemsCount,
                enabled = enabled && !searchActive,
                hasMoreSessions = hasMoreSessions,
                isLoadingMoreSessions = isLoadingMoreSessions,
                hasPageError = sessionPageError != null,
            )
        }.distinctUntilChanged().collect { shouldLoad ->
            if (shouldLoad) currentLoadMore()
        }
    }

    if (sessions.isEmpty() && !searchActive) {
        Column(
            modifier = modifier.padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("No conversations yet", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Start a new conversation above.",
                color = CelesteTextMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(top = 2.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (searchActive) {
            item(key = "search-results-header") {
                Text(
                    text = "Results",
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
                    color = CelesteTextMuted,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            items(searchResults, key = { session -> "search-${session.id}" }) { session ->
                DrawerSessionRow(
                    session = session,
                    selected = session.id == selectedSessionId,
                    enabled = enabled,
                    metadata = session.compactMetadata(showProfile),
                    preview = session.preview.takeIf(String::isNotBlank),
                    onClick = {
                        focusManager.clearFocus()
                        onSessionSelected(session)
                    },
                    onPinnedChange = { pinned -> onSessionPinnedChange(session, pinned) },
                    onRename = { title, onComplete -> onSessionRename(session, title, onComplete) },
                )
            }
            if (searchResults.isEmpty()) {
                item(key = "search-empty") {
                    Text(
                        text = when {
                            isSearchingSessions -> "Searching conversations…"
                            sessionSearchError != null -> "Could not search conversations"
                            else -> "No conversations found"
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 13.dp, vertical = 22.dp),
                        color = if (sessionSearchError == null) CelesteTextMuted else CelesteError,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else if (sessionSearchError != null) {
                item(key = "search-error") {
                    Text(
                        text = "Some results may be missing",
                        modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
                        color = CelesteError,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        } else {
            if (sections.pinned.isNotEmpty()) {
                item(key = "pinned-header") {
                    SessionSectionHeader(
                        label = "Pinned",
                        expanded = pinnedExpanded,
                        onToggle = { pinnedExpanded = !pinnedExpanded },
                    )
                }
                if (pinnedExpanded) {
                    items(sections.pinned, key = { session -> "pinned-${session.id}" }) { session ->
                        DrawerSessionRow(
                            session = session,
                            selected = session.id == selectedSessionId,
                            enabled = enabled,
                            metadata = session.compactMetadata(showProfile),
                            onClick = { onSessionSelected(session) },
                            onPinnedChange = { pinned -> onSessionPinnedChange(session, pinned) },
                            onRename = { title, onComplete -> onSessionRename(session, title, onComplete) },
                        )
                    }
                }
            }
            if (sections.recents.isNotEmpty()) {
                item(key = "recents-header") {
                    SessionSectionHeader(
                        label = "Recents",
                        expanded = recentsExpanded,
                        onToggle = { recentsExpanded = !recentsExpanded },
                    )
                }
                if (recentsExpanded) {
                    items(sections.recents, key = { session -> "recent-${session.id}" }) { session ->
                        DrawerSessionRow(
                            session = session,
                            selected = session.id == selectedSessionId,
                            enabled = enabled,
                            metadata = session.compactMetadata(showProfile),
                            onClick = { onSessionSelected(session) },
                            onPinnedChange = { pinned -> onSessionPinnedChange(session, pinned) },
                            onRename = { title, onComplete -> onSessionRename(session, title, onComplete) },
                        )
                    }
                }
            }
            if (sections.scheduled.isNotEmpty()) {
                item(key = "scheduled-header") {
                    SessionSectionHeader(
                        label = "Scheduled",
                        expanded = scheduledExpanded,
                        onToggle = { scheduledExpanded = !scheduledExpanded },
                    )
                }
                if (scheduledExpanded) {
                    items(sections.scheduled, key = { session -> "scheduled-${session.id}" }) { session ->
                        DrawerSessionRow(
                            session = session,
                            selected = session.id == selectedSessionId,
                            enabled = enabled,
                            metadata = session.compactMetadata(
                                showProfile = showProfile,
                                includeScheduledMarker = false,
                            ),
                            onClick = { onSessionSelected(session) },
                            onPinnedChange = { pinned -> onSessionPinnedChange(session, pinned) },
                            onRename = { title, onComplete -> onSessionRename(session, title, onComplete) },
                        )
                    }
                }
            }
            if (isLoadingMoreSessions) {
                item(key = "session-page-loading") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .semantics { contentDescription = "Loading older conversations" },
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = CelesteAccent,
                            strokeWidth = 2.dp,
                        )
                    }
                }
            } else if (sessionPageError != null) {
                item(key = "session-page-error") {
                    TextButton(
                        onClick = onLoadMoreSessions,
                        enabled = enabled && hasMoreSessions,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Retry loading older conversations")
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerSessionRow(
    session: StoredSession,
    selected: Boolean,
    enabled: Boolean,
    metadata: String?,
    preview: String? = null,
    onClick: () -> Unit,
    onPinnedChange: (Boolean) -> Unit,
    onRename: (String, (String?) -> Unit) -> Unit,
) {
    var menuExpanded by remember(session.id) { mutableStateOf(false) }
    var renameOpen by remember(session.id) { mutableStateOf(false) }
    val pinned = session.pinned == true

    Box {
        CelestePanel(
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    this.selected = selected
                    val states = buildList {
                        if (selected) add("Current conversation")
                        if (session.unread) add("Unread")
                    }
                    if (states.isNotEmpty()) stateDescription = states.joinToString(", ")
                    if (enabled) {
                        customActions = sessionRowAccessibilityActions(
                            pinned = pinned,
                            onPinnedChange = onPinnedChange,
                            onRename = { renameOpen = true },
                        )
                    }
                }
                .combinedClickable(
                    enabled = enabled,
                    onClick = onClick,
                    onLongClickLabel = "Conversation actions",
                    onLongClick = { menuExpanded = true },
                ),
            shape = RoundedCornerShape(if (selected) 22.dp else 12.dp),
            containerColor = if (selected) CelesteSurfaceSelected else Color.Transparent,
            borderColor = Color.Transparent,
            contentPadding = PaddingValues(horizontal = 13.dp, vertical = 9.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = session.title.ifBlank { "Untitled conversation" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    preview?.let {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = it,
                            color = CelesteTextMuted,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    metadata?.let {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = it,
                            color = CelesteTextMuted,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (session.unread) {
                    Spacer(Modifier.size(10.dp))
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .background(CelesteAccent, CircleShape),
                    )
                }
            }
        }

        SessionRowActionMenu(
            expanded = menuExpanded,
            pinned = pinned,
            onDismiss = { menuExpanded = false },
            onPinnedChange = onPinnedChange,
            onRename = { renameOpen = true },
        )
    }

    if (renameOpen) {
        RenameConversationDialog(
            sessionId = session.id,
            currentTitle = session.title,
            onDismiss = { renameOpen = false },
            onRename = onRename,
        )
    }
}

internal fun shouldRequestNextSessionPage(
    isScrollInProgress: Boolean,
    lastVisibleIndex: Int,
    totalItemCount: Int,
    enabled: Boolean,
    hasMoreSessions: Boolean,
    isLoadingMoreSessions: Boolean,
    hasPageError: Boolean,
): Boolean =
    isScrollInProgress &&
        enabled &&
        hasMoreSessions &&
        !isLoadingMoreSessions &&
        !hasPageError &&
        totalItemCount > 0 &&
        lastVisibleIndex >= totalItemCount - 3
