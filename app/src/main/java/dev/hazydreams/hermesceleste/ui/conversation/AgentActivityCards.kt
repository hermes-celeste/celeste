package dev.hazydreams.hermesceleste.ui.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hazydreams.hermesceleste.network.ActivityCapabilityState
import dev.hazydreams.hermesceleste.network.ActivityItem
import dev.hazydreams.hermesceleste.network.ActivityPresentationState
import dev.hazydreams.hermesceleste.network.AgentActivityProjection
import dev.hazydreams.hermesceleste.network.CorrelationQuality
import dev.hazydreams.hermesceleste.network.DisplayedDetail
import dev.hazydreams.hermesceleste.network.ReasoningPhase
import dev.hazydreams.hermesceleste.network.ServerReasoningActivity
import dev.hazydreams.hermesceleste.network.ToolActivity
import dev.hazydreams.hermesceleste.network.ToolPhase
import dev.hazydreams.hermesceleste.ui.CelesteBlue
import dev.hazydreams.hermesceleste.ui.CelesteGoldText
import dev.hazydreams.hermesceleste.ui.CelesteHairline
import dev.hazydreams.hermesceleste.ui.CelesteInk
import dev.hazydreams.hermesceleste.ui.CelesteMuted
import dev.hazydreams.hermesceleste.ui.CelestePanel
import dev.hazydreams.hermesceleste.ui.CelestePanelRaised

/**
 * Compose-only identity for activity rows. The reducer owns the source identity;
 * this helper only makes duplicate or malformed UI keys safe for LazyColumn and
 * local recomposition.
 */
internal fun activityItemKeys(items: List<ActivityItem>): List<String> {
    val occurrences = mutableMapOf<String, Int>()
    return items.map { item ->
        val base = item.uiKey.takeIf(String::isNotBlank) ?: when (item) {
            is ToolActivity -> "fallback:tool:${item.callId ?: item.name}"
            is ServerReasoningActivity -> "fallback:reasoning:${item.source}"
        }
        val occurrence = occurrences.getOrDefault(base, 0) + 1
        occurrences[base] = occurrence
        val key = "activity-ui:${base.length}:$base"
        if (occurrence == 1) key else "$key:occurrence:$occurrence"
    }
}

@Composable
internal fun AgentActivityPanel(
    projection: AgentActivityProjection,
    reasoningDisclosureEnabled: Boolean,
    onReasoningDisclosureChange: (Boolean) -> Unit,
) {
    var expanded by rememberSaveable(
        projection.originKey,
        projection.profile,
        projection.storedSessionId,
    ) { mutableStateOf(false) }
    val activityScopeKey = "${projection.originKey}|${projection.profile}|${projection.storedSessionId}"
    var expandedItemKey by remember(activityScopeKey) { mutableStateOf<String?>(null) }
    val keys = remember(projection.items) { activityItemKeys(projection.items) }
    val hasReasoningCapability =
        projection.capability == ActivityCapabilityState.ToolAndServerReasoning ||
            projection.serverReasoningAllowed == true
    val stateLabel = activityPresentationLabel(projection.presentation)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable { expanded = !expanded }
                .semantics {
                    contentDescription = "Agent activity, $stateLabel"
                    stateDescription = if (expanded) "Expanded" else "Collapsed"
                    role = Role.Button
                }
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Agent activity",
                    color = CelesteInk,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stateLabel,
                    color = activityPresentationColor(projection.presentation),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                    },
                )
            }
            if (projection.items.isNotEmpty()) {
                Text(
                    text = projection.items.size.toString(),
                    color = CelesteMuted,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(end = 10.dp),
                )
            }
            Text(
                text = if (expanded) "⌃" else "⌄",
                color = CelesteBlue,
                fontSize = 20.sp,
                modifier = Modifier.semantics {
                    contentDescription = if (expanded) "Collapse activity" else "Expand activity"
                },
            )
        }

        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .background(CelestePanel, RoundedCornerShape(16.dp))
                    .border(1.dp, CelesteHairline, RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (hasReasoningCapability) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .semantics {
                                contentDescription = "Show server-provided reasoning"
                                stateDescription = if (reasoningDisclosureEnabled) "On" else "Off"
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "Server-provided reasoning",
                                color = CelesteInk,
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                text = if (reasoningDisclosureEnabled) {
                                    "Only explicitly labelled Hermes content is shown."
                                } else {
                                    "Hidden on this device; no private detail is retained here."
                                },
                                color = CelesteMuted,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Switch(
                            checked = reasoningDisclosureEnabled,
                            onCheckedChange = onReasoningDisclosureChange,
                            modifier = Modifier.semantics {
                                contentDescription = "Show server-provided reasoning"
                            },
                        )
                    }
                }

                if (projection.items.isEmpty()) {
                    Text(
                        text = when (projection.presentation) {
                            ActivityPresentationState.Discovering -> "Checking activity support."
                            ActivityPresentationState.Restoring -> "Restoring activity from Hermes."
                            ActivityPresentationState.Stale -> "Activity details will return after reconnect."
                            ActivityPresentationState.Unavailable -> "This Hermes version does not expose activity details."
                            else -> "No activity details are available for this turn."
                        },
                        color = CelesteMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        itemsIndexed(
                            items = projection.items,
                            key = { index, _ -> keys[index] },
                        ) { index, item ->
                            ActivityCard(
                                item = item,
                                expanded = expandedItemKey == keys[index],
                                onToggle = {
                                    expandedItemKey = if (expandedItemKey == keys[index]) {
                                        null
                                    } else {
                                        keys[index]
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityCard(
    item: ActivityItem,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val summary = activitySummary(item)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(CelestePanelRaised.copy(alpha = 0.72f), RoundedCornerShape(13.dp))
            .border(1.dp, CelesteHairline, RoundedCornerShape(13.dp))
            .clickable(onClick = onToggle)
            .semantics {
                contentDescription = activityContentDescription(item)
                stateDescription = if (expanded) "Expanded" else "Collapsed"
                role = Role.Button
            }
            .padding(horizontal = 13.dp, vertical = 11.dp),
    ) {
        when (item) {
            is ToolActivity -> ToolCardHeader(item, expanded)
            is ServerReasoningActivity -> ReasoningCardHeader(item, expanded)
        }
        if (!expanded) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = summary,
                color = CelesteMuted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Spacer(Modifier.height(9.dp))
            when (item) {
                is ToolActivity -> ToolCardDetails(item)
                is ServerReasoningActivity -> ReasoningCardDetails(item)
            }
        }
    }
}

@Composable
private fun ToolCardHeader(item: ToolActivity, expanded: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "Tool",
            color = CelesteGoldText,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.size(7.dp))
        Text(
            text = item.name,
            color = CelesteInk,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = toolPhaseLabel(item.phase),
            color = toolPhaseColor(item.phase),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics {
                contentDescription = "Tool, ${activityAnnouncementPhase(item.phase)}"
                liveRegion = LiveRegionMode.Polite
            },
        )
        Spacer(Modifier.size(7.dp))
        Text(if (expanded) "⌃" else "⌄", color = CelesteBlue, fontSize = 17.sp)
    }
    if (item.correlation != CorrelationQuality.ExactId) {
        Text(
            text = correlationLabel(item.correlation),
            color = CelesteMuted,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun ReasoningCardHeader(item: ServerReasoningActivity, expanded: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = item.serverLabel ?: "Server-provided reasoning",
            color = CelesteBlue,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = reasoningPhaseLabel(item.phase),
            color = CelesteBlue,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics {
                contentDescription = "Server-provided reasoning, ${reasoningAnnouncementPhase(item.phase)}"
                liveRegion = LiveRegionMode.Polite
            },
        )
        Spacer(Modifier.size(7.dp))
        Text(if (expanded) "⌃" else "⌄", color = CelesteBlue, fontSize = 17.sp)
    }
}

@Composable
private fun ToolCardDetails(item: ToolActivity) {
    if (item.input == null && item.progress == null && item.output == null) {
        DetailUnavailable()
        return
    }
    item.input?.let { ActivityDetailBlock(label = "Displayed input", detail = it) }
    item.progress?.let { ActivityDetailBlock(label = "Displayed progress", detail = it) }
    item.output?.let { ActivityDetailBlock(label = "Displayed output", detail = it) }
}

@Composable
private fun ReasoningCardDetails(item: ServerReasoningActivity) {
    if (item.phase == ReasoningPhase.Unavailable || item.text.text.isBlank()) {
        DetailUnavailable()
    } else {
        ActivityDetailBlock(label = "Server-provided reasoning", detail = item.text)
    }
}

@Composable
private fun ActivityDetailBlock(label: String, detail: DisplayedDetail) {
    var copied by remember(detail.text) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                color = CelesteMuted,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(detail.text))
                    copied = true
                },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(if (copied) "Displayed detail copied" else "Copy")
            }
        }
        SelectionContainer {
            Text(
                text = detail.text,
                color = CelesteInk,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics {
                    contentDescription = activityDetailContentDescription(label)
                },
            )
        }
        if (detail.wasTruncated) {
            Text(
                text = "More content is unavailable in this view.",
                color = CelesteMuted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (detail.wasRedacted) {
            Text(
                text = "Sensitive detail hidden.",
                color = CelesteMuted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun DetailUnavailable() {
    Text(
        text = "Details hidden or unavailable.",
        color = CelesteMuted,
        style = MaterialTheme.typography.bodySmall,
    )
}

internal fun activityAnnouncementPhase(phase: ToolPhase): String = when (phase) {
    ToolPhase.Started,
    ToolPhase.Running -> "running"
    ToolPhase.Completed -> "completed"
    ToolPhase.Failed -> "error"
    ToolPhase.Interrupted -> "interrupted"
}

internal fun reasoningAnnouncementPhase(phase: ReasoningPhase): String = when (phase) {
    ReasoningPhase.Streaming -> "streaming"
    ReasoningPhase.Complete -> "complete"
    ReasoningPhase.Unavailable -> "unavailable"
}

internal fun activityDetailContentDescription(label: String): String =
    "$label, selectable displayed detail"

private fun activityPresentationLabel(state: ActivityPresentationState): String = when (state) {
    ActivityPresentationState.Unknown -> "Checking activity support"
    ActivityPresentationState.Discovering -> "Checking activity support"
    ActivityPresentationState.Available -> "Available"
    ActivityPresentationState.Running -> "Working"
    ActivityPresentationState.Stale -> "Reconnecting"
    ActivityPresentationState.Restoring -> "Restoring activity"
    ActivityPresentationState.Unavailable -> "Unavailable"
}

private fun activityPresentationColor(state: ActivityPresentationState): Color = when (state) {
    ActivityPresentationState.Running -> CelesteGoldText
    ActivityPresentationState.Stale,
    ActivityPresentationState.Restoring,
    ActivityPresentationState.Discovering,
    ActivityPresentationState.Unknown -> CelesteBlue
    ActivityPresentationState.Available -> CelesteMuted
    ActivityPresentationState.Unavailable -> CelesteMuted
}

private fun activitySummary(item: ActivityItem): String = when (item) {
    is ToolActivity -> buildString {
        append(toolPhaseLabel(item.phase))
        if (item.progress != null) append(" · displayed progress available")
        if (item.output != null) append(" · displayed output available")
        else if (item.input != null) append(" · displayed input available")
    }
    is ServerReasoningActivity -> when (item.phase) {
        ReasoningPhase.Streaming -> "Server-provided content is streaming"
        ReasoningPhase.Complete -> "Server-provided content is complete"
        ReasoningPhase.Unavailable -> "Details hidden or unavailable"
    }
}

private fun activityContentDescription(item: ActivityItem): String = when (item) {
    is ToolActivity -> "Tool ${item.name}, ${toolPhaseLabel(item.phase)}"
    is ServerReasoningActivity -> "Server-provided reasoning, ${reasoningPhaseLabel(item.phase)}"
}

private fun toolPhaseLabel(phase: ToolPhase): String = when (phase) {
    ToolPhase.Started -> "Started"
    ToolPhase.Running -> "Running"
    ToolPhase.Completed -> "Completed"
    ToolPhase.Failed -> "Failed"
    ToolPhase.Interrupted -> "Interrupted"
}

private fun toolPhaseColor(phase: ToolPhase): Color = when (phase) {
    ToolPhase.Failed,
    ToolPhase.Interrupted -> CelesteMuted
    ToolPhase.Started,
    ToolPhase.Running -> CelesteGoldText
    ToolPhase.Completed -> CelesteInk
}

private fun reasoningPhaseLabel(phase: ReasoningPhase): String = when (phase) {
    ReasoningPhase.Streaming -> "Streaming"
    ReasoningPhase.Complete -> "Complete"
    ReasoningPhase.Unavailable -> "Unavailable"
}

private fun correlationLabel(correlation: CorrelationQuality): String = when (correlation) {
    CorrelationQuality.ExactId -> "Exact server correlation"
    CorrelationQuality.LegacyName -> "Legacy correlation"
    CorrelationQuality.Uncorrelated -> "Correlation unavailable"
}
