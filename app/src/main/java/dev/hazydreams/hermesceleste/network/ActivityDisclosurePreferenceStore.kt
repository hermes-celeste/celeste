package dev.hazydreams.hermesceleste.network

import android.content.Context

/** Stores only the device-local disclosure choice, never activity text or tool arguments. */
interface ActivityDisclosurePreferenceStore {
    fun isServerReasoningDisclosureEnabled(): Boolean
    fun setServerReasoningDisclosureEnabled(enabled: Boolean)
}

class InMemoryActivityDisclosurePreferenceStore(
    initialEnabled: Boolean = true,
) : ActivityDisclosurePreferenceStore {
    private var enabled = initialEnabled

    override fun isServerReasoningDisclosureEnabled(): Boolean = enabled

    override fun setServerReasoningDisclosureEnabled(enabled: Boolean) {
        this.enabled = enabled
    }
}

class AndroidActivityDisclosurePreferenceStore(
    context: Context,
) : ActivityDisclosurePreferenceStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun isServerReasoningDisclosureEnabled(): Boolean =
        preferences.getBoolean(REASONING_DISCLOSURE_KEY, true)

    override fun setServerReasoningDisclosureEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(REASONING_DISCLOSURE_KEY, enabled).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "celeste_activity_preferences"
        const val REASONING_DISCLOSURE_KEY = "server_reasoning_disclosure_enabled"
    }
}
