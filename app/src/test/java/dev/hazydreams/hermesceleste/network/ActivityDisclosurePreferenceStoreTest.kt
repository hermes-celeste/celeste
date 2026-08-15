package dev.hazydreams.hermesceleste.network

import android.content.SharedPreferences
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityDisclosurePreferenceStoreTest {
    @Test
    fun androidPreferencesPersistScopedChoicesWithoutStoringTheAuthorityValues() {
        val preferences = inMemorySharedPreferences()
        val scope = ActivityDisclosureScope(
            originKey = "https://hermes.test/",
            profile = "default",
            storedSessionId = "stored-42",
        )
        val otherScope = scope.copy(profile = "work")

        AndroidActivityDisclosurePreferenceStore.fromPreferences(preferences)
            .setServerReasoningDisclosureEnabled(scope, false)

        val reopened = AndroidActivityDisclosurePreferenceStore.fromPreferences(preferences)
        assertFalse(
            reopened.isServerReasoningDisclosureEnabled(
                scope.copy(originKey = "https://hermes.test"),
            ),
        )
        assertTrue(reopened.isServerReasoningDisclosureEnabled(otherScope))
        assertTrue(reopened.isServerReasoningDisclosureEnabled())

        val storedKeys = preferences.all.keys
        assertTrue(storedKeys.none { key -> key.contains("hermes.test") })
        assertTrue(storedKeys.none { key -> key.contains("stored-42") })
    }

    @Test
    fun androidPreferencesRetainGlobalDefaultAcrossStoreInstances() {
        val preferences = inMemorySharedPreferences()
        val first = AndroidActivityDisclosurePreferenceStore.fromPreferences(preferences)

        first.setServerReasoningDisclosureEnabled(false)

        val reopened = AndroidActivityDisclosurePreferenceStore.fromPreferences(preferences)
        assertFalse(reopened.isServerReasoningDisclosureEnabled())
        assertFalse(
            reopened.isServerReasoningDisclosureEnabled(
                ActivityDisclosureScope(
                    originKey = "https://other.test",
                    profile = "default",
                    storedSessionId = "other-session",
                ),
            ),
        )
    }

    @Test
    fun androidPreferencesRejectAnUnverifiedCommit() {
        val preferences = inMemorySharedPreferences(writeOnPut = false)
        val store = AndroidActivityDisclosurePreferenceStore.fromPreferences(preferences)

        assertFalse(store.setServerReasoningDisclosureEnabled(false))
        assertTrue(store.isServerReasoningDisclosureEnabled())
    }

    private fun inMemorySharedPreferences(
        writeOnPut: Boolean = true,
    ): SharedPreferences {
        val values = mutableMapOf<String, Any?>()
        lateinit var editor: SharedPreferences.Editor
        val editorHandler = InvocationHandler { _, method, args ->
            when (method.name) {
                "putBoolean" -> {
                    val arguments = requireNotNull(args)
                    if (writeOnPut) {
                        values[arguments[0] as String] = arguments[1] as Boolean
                    }
                    editor
                }
                "remove" -> {
                    val arguments = requireNotNull(args)
                    values.remove(arguments[0] as String)
                    editor
                }
                "clear" -> {
                    values.clear()
                    editor
                }
                "commit" -> true
                "apply" -> Unit
                else -> defaultReturn(method)
            }
        }
        editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
            editorHandler,
        ) as SharedPreferences.Editor

        val preferencesHandler = InvocationHandler { _, method, args ->
            when (method.name) {
                "getBoolean" -> {
                    val arguments = requireNotNull(args)
                    values[arguments[0] as String] as? Boolean ?: arguments[1] as Boolean
                }
                "getAll" -> values.toMap()
                "contains" -> {
                    val arguments = requireNotNull(args)
                    values.containsKey(arguments[0] as String)
                }
                "edit" -> editor
                else -> defaultReturn(method)
            }
        }
        return Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
            preferencesHandler,
        ) as SharedPreferences
    }

    private fun defaultReturn(method: Method): Any? = when (method.returnType) {
        Boolean::class.javaPrimitiveType -> false
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Float::class.javaPrimitiveType -> 0f
        Double::class.javaPrimitiveType -> 0.0
        else -> null
    }
}
