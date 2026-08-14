package dev.hazydreams.hermesceleste.ui.sessions

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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hazydreams.hermesceleste.network.DashboardProfile
import dev.hazydreams.hermesceleste.network.StoredSession
import dev.hazydreams.hermesceleste.ui.CelesteBackdrop
import dev.hazydreams.hermesceleste.ui.CelesteBlue

import dev.hazydreams.hermesceleste.ui.CelesteError
import dev.hazydreams.hermesceleste.ui.CelesteHairline
import dev.hazydreams.hermesceleste.ui.CelesteInk
import dev.hazydreams.hermesceleste.ui.CelesteMuted
import dev.hazydreams.hermesceleste.ui.CelestePanel

import dev.hazydreams.hermesceleste.ui.EditorialDivider
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

    CelesteBackdrop(showOrnament = false) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 44.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Conversations", style = MaterialTheme.typography.headlineLarge)
                }
                TextButton(
                    onClick = onSettings,
                    enabled = loadingMessage == null,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    Text("Settings", fontWeight = FontWeight.SemiBold)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick = { profileMenuExpanded = true },
                        enabled = loadingMessage == null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .semantics {
                                contentDescription = "Profile: ${selectedProfile.replaceFirstChar(Char::uppercase)}"
                                stateDescription = if (profileMenuExpanded) "Expanded" else "Collapsed"
                            },
                        shape = RoundedCornerShape(25.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, CelesteHairline),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = CelesteInk),
                    ) {
                        Text(
                            "${selectedProfile.replaceFirstChar(Char::uppercase)}  ↓",
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
                    enabled = loadingMessage == null,
                    modifier = Modifier.height(50.dp),
                    shape = RoundedCornerShape(25.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CelesteInk),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CelesteInk),
                ) {
                    Text("New chat  +", fontWeight = FontWeight.SemiBold)
                }
            }

            if (loadingMessage != null || errorMessage != null) {
                Column(
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    loadingMessage?.let { StatusMessage(it, CelesteBlue, showSpinner = true) }
                    errorMessage?.let { StatusMessage(it, CelesteError) }
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = 4.dp, bottom = 40.dp),
            ) {
                itemsIndexed(
                    items = sessions,
                    key = { _, session -> session.id },
                ) { _, session ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = loadingMessage == null) { onSessionSelected(session) }
                            .padding(vertical = 21.dp),
                    ) {
                        Text(
                            session.title.ifBlank { "Untitled conversation" },
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
                            "${session.messageCount} MESSAGES",
                            color = CelesteMuted,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.7.sp,
                        )
                    }
                    EditorialDivider()
                }
            }
        }
    }
}
