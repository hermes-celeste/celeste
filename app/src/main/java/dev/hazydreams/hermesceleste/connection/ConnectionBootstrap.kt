package dev.hazydreams.hermesceleste.connection

internal sealed interface ConnectionBootstrapDecision {
    data object ManualSetup : ConnectionBootstrapDecision

    data class Prefill(
        val descriptor: SavedConnectionDescriptor,
    ) : ConnectionBootstrapDecision

    data class Restore(
        val descriptor: SavedConnectionDescriptor,
        val secret: ReusableSecret?,
    ) : ConnectionBootstrapDecision
}

internal fun connectionBootstrapDecision(saved: StoredConnection?): ConnectionBootstrapDecision {
    if (saved == null) return ConnectionBootstrapDecision.ManualSetup
    val descriptor = saved.descriptor
    if (!descriptor.autoLoginEnabled || descriptor.expectsSecret && saved.secret == null) {
        return ConnectionBootstrapDecision.Prefill(descriptor)
    }
    return ConnectionBootstrapDecision.Restore(descriptor, saved.secret)
}
