package dev.hazydreams.hermesceleste.network

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest

/**
 * The local reasoning disclosure choice is scoped to the same authority chain
 * as the activity projection. It never stores activity text, tool arguments, or
 * server credentials.
 */
data class ActivityDisclosureScope(
    val originKey: NormalizedDashboardOrigin,
    val profile: String,
    val storedSessionId: String,
) {
    internal val normalizedOrigin: String get() = normalizeActivityOrigin(originKey)
    internal val normalizedProfile: String get() = profile.trim().ifBlank { "default" }
    internal val normalizedStoredSessionId: String get() = storedSessionId.trim()

    internal fun stablePreferenceKey(): String =
        sha256("$normalizedOrigin\u0000$normalizedProfile\u0000$normalizedStoredSessionId")

    private companion object {
        fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}

interface ActivityDisclosurePreferenceStore {
    fun isServerReasoningDisclosureEnabled(): Boolean
    fun setServerReasoningDisclosureEnabled(enabled: Boolean)

    /** Scoped overloads keep older callers source-compatible during migration. */
    fun isServerReasoningDisclosureEnabled(scope: ActivityDisclosureScope): Boolean =
        isServerReasoningDisclosureEnabled()

    fun setServerReasoningDisclosureEnabled(scope: ActivityDisclosureScope, enabled: Boolean) =
        setServerReasoningDisclosureEnabled(enabled)
}

class InMemoryActivityDisclosurePreferenceStore(
    initialEnabled: Boolean = true,
    private val scopedValues: MutableMap<String, Boolean> = mutableMapOf(),
) : ActivityDisclosurePreferenceStore {
    private var enabled = initialEnabled

    override fun isServerReasoningDisclosureEnabled(): Boolean = enabled

    override fun setServerReasoningDisclosureEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    override fun isServerReasoningDisclosureEnabled(scope: ActivityDisclosureScope): Boolean =
        scopedValues[scope.stablePreferenceKey()] ?: enabled

    override fun setServerReasoningDisclosureEnabled(
        scope: ActivityDisclosureScope,
        enabled: Boolean,
    ) {
        scopedValues[scope.stablePreferenceKey()] = enabled
    }
}

class AndroidActivityDisclosurePreferenceStore private constructor(
    private val preferences: SharedPreferences,
) : ActivityDisclosurePreferenceStore {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        ),
    )

    override fun isServerReasoningDisclosureEnabled(): Boolean =
        preferences.getBoolean(GLOBAL_REASONING_DISCLOSURE_KEY, true)

    override fun setServerReasoningDisclosureEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(GLOBAL_REASONING_DISCLOSURE_KEY, enabled).apply()
    }

    override fun isServerReasoningDisclosureEnabled(scope: ActivityDisclosureScope): Boolean =
        preferences.getBoolean(
            scopedKey(scope),
            preferences.getBoolean(GLOBAL_REASONING_DISCLOSURE_KEY, true),
        )

    override fun setServerReasoningDisclosureEnabled(
        scope: ActivityDisclosureScope,
        enabled: Boolean,
    ) {
        preferences.edit().putBoolean(scopedKey(scope), enabled).apply()
    }

    private fun scopedKey(scope: ActivityDisclosureScope): String =
        "$SCOPED_REASONING_DISCLOSURE_PREFIX${scope.stablePreferenceKey()}"

    companion object {
        /** JVM tests use the real SharedPreferences contract with a fake backend. */
        internal fun fromPreferences(
            preferences: SharedPreferences,
        ): AndroidActivityDisclosurePreferenceStore =
            AndroidActivityDisclosurePreferenceStore(preferences)

        private const val PREFERENCES_NAME = "celeste_activity_preferences"
        private const val GLOBAL_REASONING_DISCLOSURE_KEY = "server_reasoning_disclosure_enabled"
        private const val SCOPED_REASONING_DISCLOSURE_PREFIX = "server_reasoning_disclosure_enabled:"
    }
}
