package dev.hazydreams.hermesceleste.ui.conversation

import dev.hazydreams.hermesceleste.ActiveTurnAction
import dev.hazydreams.hermesceleste.ConversationActionModel
import dev.hazydreams.hermesceleste.ConversationActivityCandidates
import dev.hazydreams.hermesceleste.TurnState
import dev.hazydreams.hermesceleste.sanitizeFailureMessage

internal enum class ActivityOwnerKind {
    Synchronizing,
    Working,
    Streaming,
    Tool,
    Reconnecting,
    Error,
}

internal enum class ActivityAnnouncementMode {
    Polite,
    Assertive,
}

internal enum class ConversationComposerAction(val label: String) {
    SendMessage("Send message"),
    StopResponse("Stop response"),
    RetryConnection("Retry connection"),
    Retry("Retry"),
    Unavailable("Unavailable"),
}

internal data class ConversationActivityScope(
    val gatewayOrigin: String,
    val profile: String,
    val sessionId: String,
) {
    val key: String = buildString {
        append("conversation:")
        append(gatewayOrigin.activityKeyPart())
        append(':')
        append(profile.activityKeyPart())
        append(':')
        append(sessionId.activityKeyPart())
    }
}

internal data class ConversationActivityOwner(
    val kind: ActivityOwnerKind,
    val key: String,
    val label: String,
    val announcement: String,
    val announcementMode: ActivityAnnouncementMode,
)

internal data class ConversationActivityProjection(
    val scope: ConversationActivityScope,
    val owner: ConversationActivityOwner?,
    val composerAction: ConversationComposerAction,
    val draftEnabled: Boolean,
)

/**
 * Selects the one conversation activity owner from backend-authoritative state.
 * Compose only renders this projection; it does not maintain another turn flag.
 * Activity candidates are maintained by the ViewModel so streaming deltas do
 * not trigger a full-transcript scan here.
 */
internal fun selectConversationActivity(
    scope: ConversationActivityScope,
    turnState: TurnState,
    activityCandidates: ConversationActivityCandidates = ConversationActivityCandidates(),
    streamingText: String = "",
    errorMessage: String? = null,
    actionModel: ConversationActionModel = ConversationActionModel(),
): ConversationActivityProjection {
    val safeError = sanitizeFailureMessage(errorMessage)
    val pendingTool = activityCandidates.pendingTool
    val interimAssistant = activityCandidates.interimAssistant

    val owner = when {
        turnState == TurnState.Reconnecting -> ConversationActivityOwner(
            kind = ActivityOwnerKind.Reconnecting,
            key = scope.activityKey("reconnecting"),
            label = "Reconnecting to Hermes…",
            announcement = "Reconnecting to Hermes…",
            announcementMode = ActivityAnnouncementMode.Polite,
        )

        safeError != null -> ConversationActivityOwner(
            kind = ActivityOwnerKind.Error,
            key = scope.activityKey("error"),
            label = safeError,
            announcement = safeError,
            announcementMode = ActivityAnnouncementMode.Assertive,
        )

        turnState == TurnState.Synchronizing -> ConversationActivityOwner(
            kind = ActivityOwnerKind.Synchronizing,
            key = scope.activityKey("synchronizing"),
            label = "Synchronizing…",
            announcement = "Synchronizing…",
            announcementMode = ActivityAnnouncementMode.Polite,
        )

        turnState != TurnState.Running -> null

        pendingTool != null -> {
            val toolName = safeToolName(pendingTool.displayName)
            ConversationActivityOwner(
                kind = ActivityOwnerKind.Tool,
                key = scope.activityKey(
                    "tool",
                    pendingTool.identity?.takeIf(String::isNotBlank)
                        ?: "$toolName:${pendingTool.index}",
                ),
                label = "Running $toolName…",
                announcement = "Running $toolName…",
                announcementMode = ActivityAnnouncementMode.Polite,
            )
        }

        streamingText.isNotBlank() || interimAssistant != null -> ConversationActivityOwner(
            kind = ActivityOwnerKind.Streaming,
            key = scope.activityKey("streaming"),
            label = "Hermes response in progress",
            announcement = "Hermes response in progress",
            announcementMode = ActivityAnnouncementMode.Polite,
        )

        else -> ConversationActivityOwner(
            kind = ActivityOwnerKind.Working,
            key = scope.activityKey("working"),
            label = "Working…",
            announcement = "Working…",
            announcementMode = ActivityAnnouncementMode.Polite,
        )
    }

    val composerAction = when {
        turnState == TurnState.Reconnecting -> ConversationComposerAction.RetryConnection
        owner?.kind == ActivityOwnerKind.Error -> ConversationComposerAction.Retry
        turnState == TurnState.Synchronizing -> ConversationComposerAction.Unavailable
        turnState == TurnState.Running -> actionModel.activeTurn.toComposerAction()
        else -> ConversationComposerAction.SendMessage
    }
    val draftEnabled = when {
        turnState == TurnState.Idle || turnState == TurnState.Reconnecting -> true
        else -> false
    }

    return ConversationActivityProjection(
        scope = scope,
        owner = owner,
        composerAction = composerAction,
        draftEnabled = draftEnabled,
    )
}

private fun ActiveTurnAction.toComposerAction(): ConversationComposerAction = when (this) {
    ActiveTurnAction.StopResponse -> ConversationComposerAction.StopResponse
    ActiveTurnAction.Unavailable -> ConversationComposerAction.Unavailable
}

private fun ConversationActivityScope.activityKey(kind: String, identity: String? = null): String =
    buildString {
        append(key)
        append(":activity:")
        append(kind)
        identity?.let {
            append(':')
            append(it.length)
            append(':')
            append(it)
        }
    }

private fun String.activityKeyPart(): String = "$length:$this"

private fun safeToolName(rawName: String?): String {
    val cleaned = rawName.orEmpty()
        .trim()
        .filter { character ->
            character.isLetterOrDigit() || character == ' ' || character in "._:/-"
        }
        .replace(Regex("\\s+"), " ")
        .take(48)
        .trim()
    return cleaned.ifBlank { "tool" }
}
