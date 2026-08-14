package dev.hazydreams.hermesceleste.connection

internal interface ConnectionStore {
    suspend fun load(): StoredConnection?

    suspend fun replace(
        descriptor: SavedConnectionDescriptor,
        secret: ReusableSecret?,
    )

    suspend fun clearSecret()

    suspend fun forget()
}

internal class InMemoryConnectionStore(
    initial: StoredConnection? = null,
) : ConnectionStore {
    private var saved = initial

    override suspend fun load(): StoredConnection? = saved

    override suspend fun replace(
        descriptor: SavedConnectionDescriptor,
        secret: ReusableSecret?,
    ) {
        require(descriptor.expectsSecret == (secret != null)) {
            "Saved connection secret does not match its descriptor."
        }
        saved = StoredConnection(descriptor.copy(autoLoginEnabled = true), secret)
    }

    override suspend fun clearSecret() {
        saved = saved?.copy(
            descriptor = saved!!.descriptor.copy(autoLoginEnabled = false),
            secret = null,
        )
    }

    override suspend fun forget() {
        saved = null
    }
}
