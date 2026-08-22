package dev.hazydreams.hermesceleste.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.hazydreams.hermesceleste.network.DashboardProfile
import dev.hazydreams.hermesceleste.network.StoredSession
import dev.hazydreams.hermesceleste.ui.CelesteAccent
import dev.hazydreams.hermesceleste.ui.CelesteError
import dev.hazydreams.hermesceleste.ui.CelestePanel
import dev.hazydreams.hermesceleste.ui.CelesteSurfacePrimary
import dev.hazydreams.hermesceleste.ui.CelesteSurfaceRaised
import dev.hazydreams.hermesceleste.ui.CelesteSurfaceSelected
import dev.hazydreams.hermesceleste.ui.CelesteTextMuted
import dev.hazydreams.hermesceleste.ui.CelesteTextPrimary
import dev.hazydreams.hermesceleste.ui.StatusMessage
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
internal fun SessionNavigationDrawer(
    sessions: List<StoredSession>,
    profiles: List<DashboardProfile>,
    selectedProfile: String,
    selectedSessionId: String?,
    loadingMessage: String?,
    errorMessage: String?,
    hasMoreSessions: Boolean,
    isLoadingMoreSessions: Boolean,
    sessionPageError: String?,
    searchQuery: String,
    searchResults: List<StoredSession>,
    isSearchingSessions: Boolean,
    sessionSearchError: String?,
    onProfileSelected: (String) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onNewConversation: () -> Unit,
    onSessionSelected: (StoredSession) -> Unit,
    onLoadMoreSessions: () -> Unit,
    onSettings: () -> Unit,
) {
    var profileMenuExpanded by remember { mutableStateOf(false) }
    var pinnedExpanded by rememberSaveable { mutableStateOf(true) }
    var recentsExpanded by rememberSaveable { mutableStateOf(true) }
    var scheduledExpanded by rememberSaveable { mutableStateOf(true) }
    val enabled = loadingMessage == null
    val sections = sessions.toSessionSections()
    val showProfile = profiles.size > 1
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

    ModalDrawerSheet(
        modifier = Modifier
            .fillMaxWidth(0.86f)
            .widthIn(max = 344.dp),
        drawerShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
        drawerContainerColor = CelesteSurfacePrimary,
        drawerContentColor = CelesteTextPrimary,
        drawerTonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 14.dp),
        ) {
            Spacer(Modifier.height(12.dp))

            SessionSearchField(
                query = searchQuery,
                searching = isSearchingSessions,
                enabled = enabled,
                onQueryChange = onSearchQueryChange,
            )
            Spacer(Modifier.height(6.dp))

            TextButton(
                onClick = onNewConversation,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.textButtonColors(
                    containerColor = Color.Transparent,
                    contentColor = CelesteTextPrimary,
                    disabledContainerColor = Color.Transparent,
                    disabledContentColor = CelesteTextMuted,
                ),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = NewConversationIcon,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.size(13.dp))
                    Text(
                        text = "New chat",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            if (loadingMessage != null || errorMessage != null) {
                Column(
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    loadingMessage?.let { StatusMessage(it, CelesteAccent, showSpinner = true) }
                    errorMessage?.let { StatusMessage(it, CelesteError) }
                }
            } else {
                Spacer(Modifier.height(18.dp))
            }

            if (sessions.isEmpty() && !searchActive) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
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
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
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

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    TextButton(
                        onClick = { profileMenuExpanded = true },
                        enabled = enabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .semantics {
                                contentDescription = "New conversation profile: ${selectedProfile.replaceFirstChar(Char::uppercase)}"
                                stateDescription = if (profileMenuExpanded) "Expanded" else "Collapsed"
                            },
                        shape = RoundedCornerShape(17.dp),
                        colors = ButtonDefaults.textButtonColors(
                            containerColor = Color.Transparent,
                            contentColor = CelesteTextPrimary,
                            disabledContainerColor = Color.Transparent,
                            disabledContentColor = CelesteTextMuted,
                        ),
                        contentPadding = PaddingValues(horizontal = 7.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .background(CelesteSurfaceSelected, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = selectedProfile.take(1).uppercase(),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 9.dp),
                            ) {
                                Text(
                                    text = selectedProfile.replaceFirstChar(Char::uppercase),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text("⌄", color = CelesteTextMuted)
                        }
                    }
                    DropdownMenu(
                        expanded = profileMenuExpanded,
                        onDismissRequest = { profileMenuExpanded = false },
                        containerColor = CelesteSurfaceRaised,
                    ) {
                        profiles.forEach { profile ->
                            DropdownMenuItem(
                                text = {
                                    Text(if (profile.isDefault) "${profile.name} · default" else profile.name)
                                },
                                onClick = {
                                    onProfileSelected(profile.name)
                                    profileMenuExpanded = false
                                },
                            )
                        }
                    }
                }

                TextButton(
                    onClick = onSettings,
                    enabled = enabled,
                    modifier = Modifier.size(48.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.textButtonColors(
                        containerColor = CelesteSurfaceRaised,
                        contentColor = CelesteTextPrimary,
                        disabledContainerColor = CelesteSurfaceRaised,
                        disabledContentColor = CelesteTextMuted,
                    ),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Icon(
                        imageVector = DrawerSettingsIcon,
                        contentDescription = "Settings",
                        modifier = Modifier.size(21.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionSearchField(
    query: String,
    searching: Boolean,
    enabled: Boolean,
    onQueryChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .semantics {
                contentDescription = "Search conversations"
                if (searching) stateDescription = "Searching"
            },
        enabled = enabled,
        placeholder = {
            Text(
                text = "Search conversations",
                color = CelesteTextMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        leadingIcon = {
            Icon(
                imageVector = SearchSessionsIcon,
                contentDescription = null,
                modifier = Modifier.size(19.dp),
                tint = CelesteTextMuted,
            )
        },
        trailingIcon = when {
            searching -> ({
                CircularProgressIndicator(
                    modifier = Modifier.size(17.dp),
                    color = CelesteAccent,
                    strokeWidth = 2.dp,
                )
            })
            query.isNotEmpty() -> ({
                IconButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier.semantics { contentDescription = "Clear conversation search" },
                ) {
                    Text("×", color = CelesteTextMuted, style = MaterialTheme.typography.titleMedium)
                }
            })
            else -> null
        },
        singleLine = true,
        shape = RoundedCornerShape(18.dp),
        textStyle = MaterialTheme.typography.bodyMedium,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color.Transparent,
            unfocusedBorderColor = Color.Transparent,
            disabledBorderColor = Color.Transparent,
            focusedContainerColor = CelesteSurfaceRaised,
            unfocusedContainerColor = CelesteSurfaceRaised,
            disabledContainerColor = CelesteSurfaceRaised,
            cursorColor = CelesteAccent,
        ),
    )
}

@Composable
private fun DrawerSessionRow(
    session: StoredSession,
    selected: Boolean,
    enabled: Boolean,
    metadata: String?,
    preview: String? = null,
    onClick: () -> Unit,
) {
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
            }
            .clickable(enabled = enabled, onClick = onClick),
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

private val NewConversationIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "New chat",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(5f, 5f)
            lineTo(19f, 5f)
            lineTo(19f, 19f)
            lineTo(5f, 19f)
            close()
            moveTo(12f, 8.5f)
            verticalLineTo(15.5f)
            moveTo(8.5f, 12f)
            horizontalLineTo(15.5f)
        }
    }.build()
}

private val SearchSessionsIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Search conversations",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(10.8f, 5f)
            curveTo(7.6f, 5f, 5f, 7.6f, 5f, 10.8f)
            curveTo(5f, 14f, 7.6f, 16.6f, 10.8f, 16.6f)
            curveTo(14f, 16.6f, 16.6f, 14f, 16.6f, 10.8f)
            curveTo(16.6f, 7.6f, 14f, 5f, 10.8f, 5f)
            close()
            moveTo(15f, 15f)
            lineTo(19f, 19f)
        }
    }.build()
}

private val DrawerSettingsIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Settings",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
        ) {
            moveTo(4f, 7f)
            horizontalLineTo(20f)
            moveTo(9f, 5f)
            verticalLineTo(9f)
            moveTo(4f, 12f)
            horizontalLineTo(20f)
            moveTo(15f, 10f)
            verticalLineTo(14f)
            moveTo(4f, 17f)
            horizontalLineTo(20f)
            moveTo(8f, 15f)
            verticalLineTo(19f)
        }
    }.build()
}
