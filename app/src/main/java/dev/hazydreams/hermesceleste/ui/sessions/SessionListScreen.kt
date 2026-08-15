package dev.hazydreams.hermesceleste.ui.sessions

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hazydreams.hermesceleste.SessionCatalogState
import dev.hazydreams.hermesceleste.SessionCatalogStatus
import dev.hazydreams.hermesceleste.network.DashboardProfile
import dev.hazydreams.hermesceleste.network.StoredSession
import dev.hazydreams.hermesceleste.ui.CelesteBackdrop
import dev.hazydreams.hermesceleste.ui.CelesteBlue
import dev.hazydreams.hermesceleste.ui.CelesteError
import dev.hazydreams.hermesceleste.ui.CelesteHairline
import dev.hazydreams.hermesceleste.ui.CelesteInk
import dev.hazydreams.hermesceleste.ui.CelesteMuted
import dev.hazydreams.hermesceleste.ui.CelestePanel
import dev.hazydreams.hermesceleste.ui.CelestePaper
import dev.hazydreams.hermesceleste.ui.EditorialDivider
import dev.hazydreams.hermesceleste.ui.StatusMessage
import java.util.Locale

@Composable
internal fun SessionListScreen(
    sessions: List<StoredSession>,
    profiles: List<DashboardProfile>,
    selectedProfile: String,
    loadingMessage: String?,
    errorMessage: String?,
    onProfileSelected: (String) -> Unit,
    onNewConversation: () -> Unit,
    onSessionSelected: (StoredSession) -> Unit,
    onSettings: () -> Unit,
    catalogState: SessionCatalogState? = null,
    query: String = "",
    onQueryChange: (String) -> Unit = {},
    onRefresh: () -> Unit = {},
    onRetry: () -> Unit = onRefresh,
    onBack: (() -> Unit)? = null,
) {
    var profileMenuExpanded by remember { mutableStateOf(false) }
    val catalog = (catalogState ?: legacyCatalogState(
        sessions = sessions,
        selectedProfile = selectedProfile,
        loadingMessage = loadingMessage,
        errorMessage = errorMessage,
    )).withQuery(query)
    val visibleRows = catalog.filteredRows
    val visibleStatus = catalog.status
    val controlsBusy = catalog.phase == SessionCatalogStatus.Loading ||
        catalog.phase == SessionCatalogStatus.Refreshing ||
        catalog.phase == SessionCatalogStatus.Reconnecting

    CelesteBackdrop(showOrnament = false) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 30.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f)) {
                    if (onBack != null) {
                        TextButton(
                            onClick = onBack,
                            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                        ) {
                            Text("←  Back", color = CelesteBlue, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Text("Conversations", style = MaterialTheme.typography.headlineLarge)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "New conversations use ${selectedProfile.trim().ifBlank { "default" }} profile",
                        color = CelesteMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                TextButton(
                    onClick = onSettings,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    Text("Settings", fontWeight = FontWeight.SemiBold)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick = { profileMenuExpanded = true },
                        enabled = profiles.isNotEmpty() && !controlsBusy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .semantics {
                                contentDescription =
                                    "New conversation profile: ${selectedProfile.replaceFirstChar(Char::uppercase)}. This does not filter the loaded conversation list."
                                stateDescription = if (profileMenuExpanded) "Expanded" else "Collapsed"
                            },
                        shape = RoundedCornerShape(25.dp),
                        border = BorderStroke(1.dp, CelesteHairline),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = CelesteInk),
                    ) {
                        Text(
                            "New: ${selectedProfile.replaceFirstChar(Char::uppercase)}  ↓",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    DropdownMenu(
                        expanded = profileMenuExpanded,
                        onDismissRequest = { profileMenuExpanded = false },
                        containerColor = CelestePanel,
                    ) {
                        profiles.forEach { profile ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (profile.isDefault) "${profile.name} · default" else profile.name,
                                    )
                                },
                                onClick = {
                                    onProfileSelected(profile.name)
                                    profileMenuExpanded = false
                                },
                            )
                        }
                    }
                }
                OutlinedButton(
                    onClick = onNewConversation,
                    enabled = !controlsBusy,
                    modifier = Modifier.height(50.dp),
                    shape = RoundedCornerShape(25.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    border = BorderStroke(1.dp, CelesteInk),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CelesteInk),
                ) {
                    Text("New chat  +", fontWeight = FontWeight.SemiBold)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    enabled = catalog.phase != SessionCatalogStatus.NotReady,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = "Search loaded conversations" },
                    singleLine = true,
                    label = { Text("Search loaded") },
                    placeholder = { Text("Title, preview, profile, source, or ID") },
                    shape = RoundedCornerShape(22.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CelesteBlue,
                        unfocusedBorderColor = CelesteHairline,
                        focusedContainerColor = CelestePaper,
                        unfocusedContainerColor = CelestePaper,
                        cursorColor = CelesteBlue,
                    ),
                )
                TextButton(
                    onClick = onRefresh,
                    enabled = !controlsBusy,
                    modifier = Modifier.semantics {
                        contentDescription = "Refresh conversations"
                        stateDescription = if (catalog.phase == SessionCatalogStatus.Refreshing) {
                            "Refreshing"
                        } else {
                            "Ready"
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                ) {
                    Text("Refresh", color = CelesteBlue, fontWeight = FontWeight.SemiBold)
                }
            }

            Text(
                "Search is limited to loaded conversations.",
                color = CelesteMuted,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 6.dp),
            )

            when (visibleStatus) {
                SessionCatalogStatus.Loading -> {
                    Column(
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        StatusMessage("Loading conversations…", CelesteBlue, showSpinner = true)
                        repeat(4) { ConversationSkeleton() }
                    }
                }

                SessionCatalogStatus.Refreshing -> {
                    Column(Modifier.padding(horizontal = 28.dp, vertical = 8.dp)) {
                        StatusMessage(
                            "Refreshing conversations…",
                            CelesteBlue,
                            showSpinner = true,
                        )
                    }
                }

                SessionCatalogStatus.Reconnecting -> {
                    Column(
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StatusMessage("Reconnecting conversation scope…", CelesteBlue, showSpinner = true)
                        TextButton(
                            onClick = onRetry,
                            modifier = Modifier.semantics { contentDescription = "Retry conversation connection" },
                        ) { Text("Retry", color = CelesteBlue) }
                    }
                }

                SessionCatalogStatus.Error -> CatalogMessage(
                    message = catalog.errorMessage ?: "Could not load conversations.",
                    actionLabel = "Retry",
                    onAction = onRetry,
                )

                SessionCatalogStatus.Stale -> CatalogMessage(
                    message = catalog.errorMessage ?: "Showing the last loaded conversations.",
                    actionLabel = "Retry",
                    onAction = onRetry,
                )

                SessionCatalogStatus.Empty -> EmptyCatalog(onNewConversation)
                SessionCatalogStatus.NoResults -> CatalogMessage(
                    message = "No loaded conversation matches that search.",
                    actionLabel = "Clear search",
                    onAction = { onQueryChange("") },
                )

                SessionCatalogStatus.Ready,
                SessionCatalogStatus.Opening,
                SessionCatalogStatus.ActionInFlight,
                SessionCatalogStatus.NotReady,
                -> Unit
            }

            if (visibleRows.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = 4.dp, bottom = 40.dp),
                ) {
                    items(
                        items = visibleRows,
                        key = { session ->
                            sessionRowKey(session, catalog.scope?.originKey.orEmpty())
                        },
                    ) { session ->
                        SessionRow(
                            session = session,
                            showProfile = profiles.size > 1,
                            enabled = !controlsBusy,
                            onClick = { onSessionSelected(session) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionRow(
    session: StoredSession,
    showProfile: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val title = session.title.ifBlank { "Untitled conversation" }
    val metadata = buildList {
        if (showProfile && session.profile.isNotBlank()) add(session.profile)
        if (session.messageCount > 0) add("${session.messageCount} messages")
        if (session.source.isNotBlank()) add(session.source)
    }.joinToString("  ·  ").ifBlank { "No messages yet" }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = "$title. $metadata. Open conversation."
                stateDescription = if (enabled) "Ready" else "Unavailable"
            }
            .padding(vertical = 19.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (session.preview.isNotBlank()) {
            Spacer(Modifier.height(9.dp))
            Text(
                session.preview,
                color = CelesteMuted,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(13.dp))
        Text(
            metadata.uppercase(),
            color = CelesteMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.7.sp,
        )
    }
    EditorialDivider()
}

@Composable
private fun ConversationSkeleton() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth(0.72f).height(22.dp).background(CelestePanel, RoundedCornerShape(5.dp)))
        Box(Modifier.fillMaxWidth(0.94f).height(14.dp).background(CelestePanel, RoundedCornerShape(5.dp)))
        Box(Modifier.fillMaxWidth(0.32f).height(11.dp).background(CelestePanel, RoundedCornerShape(5.dp)))
    }
}

@Composable
private fun CatalogMessage(
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = 28.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        StatusMessage(message, CelesteError)
        TextButton(
            onClick = onAction,
            modifier = Modifier.semantics { contentDescription = actionLabel },
        ) { Text(actionLabel, color = CelesteBlue, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun EmptyCatalog(onNewConversation: () -> Unit) {
    Column(
        modifier = Modifier.padding(horizontal = 28.dp, vertical = 26.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("No conversations loaded", style = MaterialTheme.typography.titleLarge)
        Text(
            "Conversations created on Hermes will appear here after they have durable server identity.",
            color = CelesteMuted,
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(
            onClick = onNewConversation,
            colors = ButtonDefaults.buttonColors(containerColor = CelesteInk, contentColor = CelestePaper),
        ) { Text("Start a new conversation") }
    }
}

private fun sessionRowKey(session: StoredSession, originKey: String): String =
    "$originKey\u0000${session.profile.trim().lowercase(Locale.ROOT)}\u0000${session.id.trim()}"

private fun legacyCatalogState(
    sessions: List<StoredSession>,
    selectedProfile: String,
    loadingMessage: String?,
    errorMessage: String?,
): SessionCatalogState {
    val phase = when {
        loadingMessage != null -> SessionCatalogStatus.Loading
        errorMessage != null && sessions.isNotEmpty() -> SessionCatalogStatus.Stale
        errorMessage != null -> SessionCatalogStatus.Error
        sessions.isEmpty() -> SessionCatalogStatus.Empty
        else -> SessionCatalogStatus.Ready
    }
    return SessionCatalogState(
        phase = phase,
        scope = dev.hazydreams.hermesceleste.SessionScope.from("legacy", selectedProfile),
        rows = sessions,
        errorMessage = errorMessage,
    )
}
