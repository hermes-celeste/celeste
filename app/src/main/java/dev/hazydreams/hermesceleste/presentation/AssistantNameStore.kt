package dev.hazydreams.hermesceleste.presentation

import android.content.Context
import android.util.Log
import dev.hazydreams.hermesceleste.network.DashboardUrlPolicy
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromJsonElement

const val DEFAULT_ASSISTANT_NAME = "Hermes"

internal const val ASSISTANT_NAME_MAX_CODE_POINTS = 40
internal const val ASSISTANT_NAME_STORE_VERSION = 1

internal enum class AssistantNameDiagnostic(val code: String) {
    ReadFailure("read_failure"),
    MalformedPayload("malformed_payload"),
    MalformedRecord("malformed_record"),
    UnsupportedVersion("unsupported_version"),
    InvalidRecord("invalid_record"),
}

internal fun interface AssistantNameDiagnostics {
    fun record(diagnostic: AssistantNameDiagnostic)
}

internal object NoOpAssistantNameDiagnostics : AssistantNameDiagnostics {
    override fun record(diagnostic: AssistantNameDiagnostic) = Unit
}

internal object LogcatAssistantNameDiagnostics : AssistantNameDiagnostics {
    private const val TAG = "CelesteAssistantName"

    override fun record(diagnostic: AssistantNameDiagnostic) {
        Log.w(TAG, "Local assistant-name record discarded: ${diagnostic.code}")
    }
}

internal data class AssistantNameValidation(
    val normalized: String?,
    val errorMessage: String?,
)

internal object AssistantNamePolicy {
    fun validate(raw: String): AssistantNameValidation {
        if (containsForbiddenCodePoint(raw)) {
            return AssistantNameValidation(
                normalized = null,
                errorMessage = "Use a single line without control characters.",
            )
        }

        val normalized = trimUnicodeWhitespace(raw)
        if (normalized.isEmpty()) {
            return AssistantNameValidation(normalized = null, errorMessage = null)
        }

        if (normalized.codePointCount(0, normalized.length) > ASSISTANT_NAME_MAX_CODE_POINTS) {
            return AssistantNameValidation(
                normalized = null,
                errorMessage = "Use $ASSISTANT_NAME_MAX_CODE_POINTS Unicode code points or fewer.",
            )
        }

        return AssistantNameValidation(normalized = normalized, errorMessage = null)
    }

    internal fun requireValid(raw: String?): String? {
        if (raw == null) return null
        val result = validate(raw)
        require(result.errorMessage == null) { result.errorMessage.orEmpty() }
        return result.normalized
    }

    private fun containsForbiddenCodePoint(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            if (
                Character.isISOControl(codePoint) ||
                codePoint == 0x2028 ||
                codePoint == 0x2029
            ) {
                return true
            }
            index += Character.charCount(codePoint)
        }
        return false
    }

    private fun trimUnicodeWhitespace(value: String): String {
        var start = 0
        var end = value.length
        while (start < end) {
            val codePoint = value.codePointAt(start)
            if (!isUnicodeWhitespace(codePoint)) break
            start += Character.charCount(codePoint)
        }
        while (end > start) {
            val codePoint = value.codePointBefore(end)
            if (!isUnicodeWhitespace(codePoint)) break
            end -= Character.charCount(codePoint)
        }
        return value.substring(start, end)
    }

    private fun isUnicodeWhitespace(codePoint: Int): Boolean =
        Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
}

internal data class AssistantNameKey(
    val origin: String,
    val profile: String,
) {
    companion object {
        fun from(baseUrl: String?, selectedProfile: String?): AssistantNameKey? {
            if (baseUrl.isNullOrBlank() || selectedProfile == null) return null
            val origin = runCatching { DashboardUrlPolicy.normalize(baseUrl) }.getOrNull()
                ?: return null
            return AssistantNameKey(
                origin = origin,
                profile = selectedProfile.trim().ifEmpty { "default" },
            )
        }
    }
}

internal interface AssistantNameStore {
    suspend fun read(origin: String, profile: String): String?

    suspend fun write(origin: String, profile: String, name: String?)

    suspend fun clearOrigin(origin: String)
}

internal interface AssistantNameRecordStorage {
    fun readRecords(): String?

    fun commitRecords(encoded: String?): Boolean
}

@Serializable
internal data class AssistantNameRecord(
    val version: Int,
    val origin: String,
    val profile: String,
    val name: String,
)

internal fun interface AssistantNameRecordCommitter {
    fun commit(encoded: String?): Boolean
}

internal fun commitAssistantNameRecords(
    records: List<AssistantNameRecord>,
    json: Json,
    committer: AssistantNameRecordCommitter,
) {
    val encoded = records
        .takeIf { it.isNotEmpty() }
        ?.let { json.encodeToString(it) }
    if (!committer.commit(encoded)) {
        throw IOException("Could not save assistant name on this device.")
    }
}

internal fun decodeAssistantNameRecords(
    encoded: String,
    json: Json,
    diagnostics: AssistantNameDiagnostics,
): List<AssistantNameRecord> {
    val root = runCatching { json.parseToJsonElement(encoded) }.getOrElse {
        diagnostics.record(AssistantNameDiagnostic.MalformedPayload)
        return emptyList()
    }
    val records = root as? JsonArray ?: run {
        diagnostics.record(AssistantNameDiagnostic.MalformedPayload)
        return emptyList()
    }

    return records.mapNotNull { element ->
        val record = runCatching {
            json.decodeFromJsonElement<AssistantNameRecord>(element)
        }.getOrElse {
            diagnostics.record(AssistantNameDiagnostic.MalformedRecord)
            return@mapNotNull null
        }
        if (record.version != ASSISTANT_NAME_STORE_VERSION) {
            diagnostics.record(AssistantNameDiagnostic.UnsupportedVersion)
            return@mapNotNull null
        }
        val key = AssistantNameKey.from(record.origin, record.profile)
            ?: run {
                diagnostics.record(AssistantNameDiagnostic.InvalidRecord)
                return@mapNotNull null
            }
        val validatedName = AssistantNamePolicy.validate(record.name)
        if (validatedName.errorMessage != null || validatedName.normalized == null) {
            diagnostics.record(AssistantNameDiagnostic.InvalidRecord)
            return@mapNotNull null
        }
        AssistantNameRecord(
            version = ASSISTANT_NAME_STORE_VERSION,
            origin = key.origin,
            profile = key.profile,
            name = validatedName.normalized,
        )
    }
}

internal class InMemoryAssistantNameStore(
    private val entries: MutableMap<AssistantNameKey, String> = linkedMapOf(),
) : AssistantNameStore {
    private val mutex = Mutex()

    override suspend fun read(origin: String, profile: String): String? = mutex.withLock {
        entries[storeKey(origin, profile)]
    }

    override suspend fun write(origin: String, profile: String, name: String?) {
        val normalized = AssistantNamePolicy.requireValid(name)
        mutex.withLock {
            val key = storeKey(origin, profile)
            if (normalized == null) {
                entries.remove(key)
            } else {
                entries[key] = normalized
            }
        }
    }

    override suspend fun clearOrigin(origin: String) {
        val normalizedOrigin = storeOrigin(origin)
        mutex.withLock {
            entries.keys.removeAll { it.origin == normalizedOrigin }
        }
    }
}

internal class AndroidAssistantNameStore(
    private val recordStorage: AssistantNameRecordStorage,
    private val json: Json = Json { ignoreUnknownKeys = false },
    private val diagnostics: AssistantNameDiagnostics = NoOpAssistantNameDiagnostics,
) : AssistantNameStore {
    constructor(
        context: Context,
        json: Json = Json { ignoreUnknownKeys = false },
        diagnostics: AssistantNameDiagnostics = LogcatAssistantNameDiagnostics,
    ) : this(
        recordStorage = SharedPreferencesAssistantNameRecordStorage(context),
        json = json,
        diagnostics = diagnostics,
    )

    private val mutex = Mutex()

    override suspend fun read(origin: String, profile: String): String? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val key = storeKey(origin, profile)
            readRecords().firstOrNull { it.origin == key.origin && it.profile == key.profile }?.name
        }
    }

    override suspend fun write(origin: String, profile: String, name: String?) {
        withContext(Dispatchers.IO) {
            val normalized = AssistantNamePolicy.requireValid(name)
            mutex.withLock {
                val key = storeKey(origin, profile)
                val records = readRecords()
                    .filterNot { it.origin == key.origin && it.profile == key.profile }
                    .toMutableList()
                if (normalized != null) {
                    records += AssistantNameRecord(
                        version = ASSISTANT_NAME_STORE_VERSION,
                        origin = key.origin,
                        profile = key.profile,
                        name = normalized,
                    )
                }
                commitRecords(records)
            }
        }
    }

    override suspend fun clearOrigin(origin: String) {
        val normalizedOrigin = storeOrigin(origin)
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val records = readRecords().filterNot { it.origin == normalizedOrigin }
                commitRecords(records)
            }
        }
    }

    private fun readRecords(): List<AssistantNameRecord> {
        val encoded = recordStorage.readRecords() ?: return emptyList()
        return decodeAssistantNameRecords(encoded, json, diagnostics)
    }

    private fun commitRecords(records: List<AssistantNameRecord>) {
        commitAssistantNameRecords(records, json) { encoded ->
            recordStorage.commitRecords(encoded)
        }
    }
}

private class SharedPreferencesAssistantNameRecordStorage(
    context: Context,
) : AssistantNameRecordStorage {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun readRecords(): String? = preferences.getString(KEY_RECORDS, null)

    override fun commitRecords(encoded: String?): Boolean {
        val editor = preferences.edit()
        if (encoded == null) {
            editor.remove(KEY_RECORDS)
        } else {
            editor.putString(KEY_RECORDS, encoded)
        }
        return editor.commit()
    }

    private companion object {
        const val PREFERENCES_NAME = "assistant_presentation_v1"
        const val KEY_RECORDS = "records"
    }
}

private fun storeKey(origin: String, profile: String): AssistantNameKey =
    AssistantNameKey.from(origin, profile)
        ?: throw IllegalArgumentException("A valid assistant name context is required.")

private fun storeOrigin(origin: String): String =
    AssistantNameKey.from(origin, "default")?.origin
        ?: throw IllegalArgumentException("A valid assistant name origin is required.")
