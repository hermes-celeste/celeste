package dev.hazydreams.hermesceleste.ui.sessions

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hazydreams.hermesceleste.network.DashboardProfile
import dev.hazydreams.hermesceleste.network.StoredSession
import dev.hazydreams.hermesceleste.ui.CelesteScreen
import dev.hazydreams.hermesceleste.ui.CelesteAccent
import dev.hazydreams.hermesceleste.ui.CelesteAccentContent
import dev.hazydreams.hermesceleste.ui.CelesteError
import dev.hazydreams.hermesceleste.ui.CelesteHairline
import dev.hazydreams.hermesceleste.ui.CelesteTextPrimary
import dev.hazydreams.hermesceleste.ui.CelesteTextMuted
import dev.hazydreams.hermesceleste.ui.CelesteSurfacePrimary
import dev.hazydreams.hermesceleste.ui.CelestePanel
import dev.hazydreams.hermesceleste.ui.StatusMessage

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
) {
    var profileMenuExpanded by remember { mutableStateOf(false) }

    CelesteScreen {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            ) {
                Text(
                    text = "Celeste",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.align(Alignment.Center),
                )
                TextButton(
                    onClick = onSettings,
                    enabled = loadingMessage == null,
                    modifier = Modifier.align(Alignment.CenterEnd),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    Text("Settings", color = CelesteTextPrimary, fontWeight = FontWeight.SemiBold)
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
            ) {
                OutlinedButton(
                    onClick = { profileMenuExpanded = true },
                    enabled = loadingMessage == null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .semantics {
                            contentDescription = "Profile: ${selectedProfile.replaceFirstChar(Char::uppercase)}"
                            stateDescription = if (profileMenuExpanded) "Expanded" else "Collapsed"
                        },
                    shape = RoundedCornerShape(23.dp),
                    border = BorderStroke(1.dp, CelesteHairline),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = CelesteSurfacePrimary,
                        contentColor = CelesteTextPrimary,
                    ),
                ) {
                    Text(
                        text = "${selectedProfile.replaceFirstChar(Char::uppercase)}  ↓",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                DropdownMenu(
                    expanded = profileMenuExpanded,
                    onDismissRequest = { profileMenuExpanded = false },
                    containerColor = CelesteSurfacePrimary,
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

            if (loadingMessage != null || errorMessage != null) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    loadingMessage?.let { StatusMessage(it, CelesteAccent, showSpinner = true) }
                    errorMessage?.let { StatusMessage(it, CelesteError) }
                }
            }

            if (sessions.isEmpty()) {
                EmptyConversationState(
                    modifier = Modifier.weight(1f),
                    enabled = loadingMessage == null,
                    onNewConversation = onNewConversation,
                )
            } else {
                Box(modifier = Modifier.weight(1f)) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 20.dp,
                            end = 20.dp,
                            top = 12.dp,
                            bottom = 104.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(
                            items = sessions,
                            key = { session -> session.id },
                        ) { session ->
                            ConversationCard(
                                session = session,
                                showProfile = profiles.size > 1,
                                enabled = loadingMessage == null,
                                onClick = { onSessionSelected(session) },
                            )
                        }
                    }

                    NewConversationButton(
                        enabled = loadingMessage == null,
                        onClick = onNewConversation,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .navigationBarsPadding()
                            .padding(end = 20.dp, bottom = 18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ConversationCard(
    session: StoredSession,
    showProfile: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    CelestePanel(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 15.dp),
    ) {
        Column {
            Text(
                text = session.title.ifBlank { "Untitled conversation" },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (session.preview.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = session.preview,
                    color = CelesteTextMuted,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(11.dp))
            Text(
                text = if (showProfile) {
                    "${session.profile.uppercase()}  ·  ${session.messageCount} MESSAGES"
                } else {
                    "${session.messageCount} MESSAGES"
                },
                color = CelesteTextMuted,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.7.sp,
            )
        }
    }
}

@Composable
private fun EmptyConversationState(
    modifier: Modifier,
    enabled: Boolean,
    onNewConversation: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("No conversations yet", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Start a new conversation to connect with your Hermes agent.",
            color = CelesteTextMuted,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        NewConversationButton(enabled = enabled, onClick = onNewConversation)
    }
}

@Composable
private fun NewConversationButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = CelesteAccent,
            contentColor = CelesteAccentContent,
            disabledContainerColor = CelesteHairline,
            disabledContentColor = CelesteTextMuted,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp),
    ) {
        Text("+", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.size(8.dp))
        Text("New conversation", fontWeight = FontWeight.SemiBold)
    }
}
