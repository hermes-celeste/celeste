package dev.hazydreams.hermesceleste

internal data class QueuedPrompt(
    val id: String,
    val text: String,
    val deliveryUncertain: Boolean = false,
)
