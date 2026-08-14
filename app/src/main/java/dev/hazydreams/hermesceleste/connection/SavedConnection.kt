package dev.hazydreams.hermesceleste.connection

import kotlinx.serialization.Serializable

@Serializable
internal enum class SavedAuthMode {
    Open,
    StaticToken,
    ProviderSession,
}

@Serializable
internal data class SavedConnectionDescriptor(
    val version: Int = CURRENT_VERSION,
    val baseUrl: String,
    val authMode: SavedAuthMode,
    val provider: String? = null,
    val username: String? = null,
    val expectsSecret: Boolean,
    val autoLoginEnabled: Boolean = true,
) {
    init {
        require(version == CURRENT_VERSION) { "Unsupported saved connection version." }
        require(baseUrl.isNotBlank()) { "A saved dashboard address is required." }
        require(expectsSecret == (authMode != SavedAuthMode.Open)) {
            "Saved connection secret metadata does not match its authentication mode."
        }
        if (authMode == SavedAuthMode.ProviderSession) {
            require(!provider.isNullOrBlank()) { "A saved provider is required." }
            require(!username.isNullOrBlank()) { "A saved account name is required." }
        }
    }

    companion object {
        const val CURRENT_VERSION = 1
    }
}

@JvmInline
internal value class ReusableSecret(val value: String) {
    init {
        require(value.isNotEmpty()) { "A reusable secret cannot be empty." }
    }

    override fun toString(): String = "[REDACTED]"
}

internal data class StoredConnection(
    val descriptor: SavedConnectionDescriptor,
    val secret: ReusableSecret?,
) {
    override fun toString(): String =
        "StoredConnection(descriptor=$descriptor, secret=${secret ?: "none"})"
}
