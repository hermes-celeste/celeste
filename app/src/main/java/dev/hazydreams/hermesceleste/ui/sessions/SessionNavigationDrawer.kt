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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hazydreams.hermesceleste.network.DashboardProfile
import dev.hazydreams.hermesceleste.network.StoredSession
import dev.hazydreams.hermesceleste.ui.CelesteAccent
import dev.hazydreams.hermesceleste.ui.CelesteAccentContent
import dev.hazydreams.hermesceleste.ui.CelesteError
import dev.hazydreams.hermesceleste.ui.CelesteHairline
import dev.hazydreams.hermesceleste.ui.CelestePanel
import dev.hazydreams.hermesceleste.ui.CelesteSurfacePrimary
import dev.hazydreams.hermesceleste.ui.CelesteSurfaceRaised
import dev.hazydreams.hermesceleste.ui.CelesteSurfaceSelected
import dev.hazydreams.hermesceleste.ui.CelesteTextMuted
import dev.hazydreams.hermesceleste.ui.CelesteTextPrimary
import dev.hazydreams.hermesceleste.ui.StatusMessage

@Composable
internal fun SessionNavigationDrawer(
    sessions: List<StoredSession>,
    profiles: List<DashboardProfile>,
    selectedProfile: String,
    selectedSessionId: String?,
    loadingMessage: String?,
    errorMessage: String?,
    onProfileSelected: (String) -> Unit,
    onNewConversation: () -> Unit,
    onSessionSelected: (StoredSession) -> Unit,
    onSettings: () -> Unit,
) {
    var profileMenuExpanded by remember { mutableStateOf(false) }
    val enabled = loadingMessage == null

    ModalDrawerSheet(
        modifier = Modifier
            .fillMaxWidth(0.9f)
            .widthIn(max = 360.dp),
        drawerShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
        drawerContainerColor = CelesteSurfacePrimary,
        drawerContentColor = CelesteTextPrimary,
        drawerTonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Celeste",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Conversations",
                        color = CelesteTextMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Button(
                    onClick = onNewConversation,
                    enabled = enabled,
                    modifier = Modifier.height(40.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CelesteAccent,
                        contentColor = CelesteAccentContent,
                        disabledContainerColor = CelesteHairline,
                        disabledContentColor = CelesteTextMuted,
                    ),
                    contentPadding = PaddingValues(horizontal = 15.dp),
                ) {
                    Text("+  New", fontWeight = FontWeight.SemiBold)
                }
            }

            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { profileMenuExpanded = true },
                    enabled = enabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp)
                        .semantics {
                            contentDescription = "New conversation profile: ${selectedProfile.replaceFirstChar(Char::uppercase)}"
                            stateDescription = if (profileMenuExpanded) "Expanded" else "Collapsed"
                        },
                    shape = RoundedCornerShape(21.dp),
                    border = BorderStroke(1.dp, CelesteHairline),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = CelesteSurfaceRaised,
                        contentColor = CelesteTextPrimary,
                    ),
                ) {
                    Text(
                        text = "New chat profile  ·  ${selectedProfile.replaceFirstChar(Char::uppercase)}  ↓",
                        style = MaterialTheme.typography.labelLarge,
                    )
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

            if (loadingMessage != null || errorMessage != null) {
                Column(
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    loadingMessage?.let { StatusMessage(it, CelesteAccent, showSpinner = true) }
                    errorMessage?.let { StatusMessage(it, CelesteError) }
                }
            } else {
                Spacer(Modifier.height(14.dp))
            }

            Text(
                text = "CONVERSATIONS",
                color = CelesteTextMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            )

            if (sessions.isEmpty()) {
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
                        text = "Start a new conversation from the button above.",
                        color = CelesteTextMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(
                        items = sessions,
                        key = { session -> session.id },
                    ) { session ->
                        DrawerSessionRow(
                            session = session,
                            selected = session.id == selectedSessionId,
                            showProfile = profiles.size > 1,
                            enabled = enabled,
                            onClick = { onSessionSelected(session) },
                        )
                    }
                }
            }

            CelestePanel(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                shape = RoundedCornerShape(18.dp),
                containerColor = CelesteSurfaceRaised,
                contentPadding = PaddingValues(4.dp),
            ) {
                TextButton(
                    onClick = onSettings,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(
                        text = "Settings",
                        color = CelesteTextPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun DrawerSessionRow(
    session: StoredSession,
    selected: Boolean,
    showProfile: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    CelestePanel(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                this.selected = selected
                if (selected) stateDescription = "Current conversation"
            }
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        containerColor = if (selected) CelesteSurfaceSelected else Color.Transparent,
        borderColor = if (selected) CelesteHairline else Color.Transparent,
        contentPadding = PaddingValues(horizontal = 13.dp, vertical = 11.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 5.dp)
                    .size(7.dp)
                    .background(
                        color = if (selected) CelesteAccent else Color.Transparent,
                        shape = CircleShape,
                    ),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.title.ifBlank { "Untitled conversation" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (session.preview.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = session.preview,
                        color = CelesteTextMuted,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (showProfile) {
                        "${session.profile.uppercase()}  ·  ${session.messageCount} MESSAGES"
                    } else {
                        "${session.messageCount} MESSAGES"
                    },
                    color = CelesteTextMuted,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.6.sp,
                )
            }
        }
    }
}
