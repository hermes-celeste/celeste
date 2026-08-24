package dev.hazydreams.hermesceleste

internal data class QueuedPrompt(
    val id: String,
    val text: String,
    val attachments: List<ComposerAttachment> = emptyList(),
    val deliveryUncertain: Boolean = false,
    val userMessageCountBeforeSubmit: Int? = null,
)
