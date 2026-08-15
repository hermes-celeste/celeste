package dev.hazydreams.hermesceleste.ui.conversation

import dev.hazydreams.hermesceleste.TurnState
import dev.hazydreams.hermesceleste.network.ConversationMessage

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
)

/**
 * Selects the one conversation activity owner from backend-authoritative state.
 * Compose only renders this projection; it does not maintain another turn flag.
 */
internal fun selectConversationActivity(
    scope: ConversationActivityScope,
    turnState: TurnState,
    messages: List<ConversationMessage> = emptyList(),
    streamingText: String = "",
    errorMessage: String? = null,
): ConversationActivityProjection {
    val safeError = sanitizeActivityError(errorMessage)
    val pendingTool = messages.withIndex()
        .toList()
        .asReversed()
        .firstOrNull { (_, message) -> message.role == "tool" && message.pending }
    val interimAssistant = messages.asReversed()
        .firstOrNull { message ->
            message.role == "assistant" && message.interim && message.text.isNotBlank()
        }

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
            val (index, message) = pendingTool
            val toolName = safeToolName(message.toolName)
            ConversationActivityOwner(
                kind = ActivityOwnerKind.Tool,
                key = scope.activityKey(
                    "tool",
                    message.id?.takeIf(String::isNotBlank) ?: "$toolName:$index",
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
        turnState == TurnState.Running -> ConversationComposerAction.StopResponse
        else -> ConversationComposerAction.SendMessage
    }

    return ConversationActivityProjection(
        scope = scope,
        owner = owner,
        composerAction = composerAction,
    )
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

private fun sanitizeActivityError(rawMessage: String?): String? {
    val cleaned = rawMessage
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.take(240)
        ?.takeIf(String::isNotBlank)
        ?: return null
    val normalized = cleaned.lowercase()
    return if (
        normalized.contains("standalonecoroutine") ||
        normalized.contains("jobcancellationexception") ||
        (normalized.contains("coroutine") && normalized.contains("cancel"))
    ) {
        null
    } else {
        cleaned
    }
}
