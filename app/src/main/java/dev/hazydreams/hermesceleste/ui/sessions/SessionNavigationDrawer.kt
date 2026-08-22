package dev.hazydreams.hermesceleste.ui.sessions

import androidx.compose.foundation.background
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.contentDescription
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
    val enabled = loadingMessage == null
    val showProfile = profiles.size > 1

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

            SessionDrawerConversationList(
                sessions = sessions,
                selectedSessionId = selectedSessionId,
                enabled = enabled,
                showProfile = showProfile,
                hasMoreSessions = hasMoreSessions,
                isLoadingMoreSessions = isLoadingMoreSessions,
                sessionPageError = sessionPageError,
                searchQuery = searchQuery,
                searchResults = searchResults,
                isSearchingSessions = isSearchingSessions,
                sessionSearchError = sessionSearchError,
                onSessionSelected = onSessionSelected,
                onLoadMoreSessions = onLoadMoreSessions,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )

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