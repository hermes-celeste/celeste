package dev.hazydreams.hermesceleste.connection

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal class AndroidConnectionStore(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = false },
) : ConnectionStore {
    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val secretFile = File(applicationContext.noBackupFilesDir, SECRET_FILE_NAME)
    private val temporarySecretFile = File(applicationContext.noBackupFilesDir, "$SECRET_FILE_NAME.tmp")

    override suspend fun load(): StoredConnection? = withContext(Dispatchers.IO) {
        val encoded = preferences.getString(KEY_DESCRIPTOR, null) ?: return@withContext null
        val descriptor = runCatching {
            json.decodeFromString<SavedConnectionDescriptor>(encoded)
        }.getOrElse {
            forgetInternal()
            return@withContext null
        }

        if (!descriptor.expectsSecret) {
            deleteSecretMaterial()
            return@withContext StoredConnection(descriptor, null)
        }

        val secret = runCatching { decrypt(descriptor) }
            .getOrElse {
                deleteSecretMaterial()
                writeDescriptor(descriptor.copy(autoLoginEnabled = false))
                null
            }
        StoredConnection(descriptor.copy(autoLoginEnabled = descriptor.autoLoginEnabled && secret != null), secret)
    }

    override suspend fun replace(
        descriptor: SavedConnectionDescriptor,
        secret: ReusableSecret?,
    ) = withContext(Dispatchers.IO) {
        require(descriptor.expectsSecret == (secret != null)) {
            "Saved connection secret does not match its descriptor."
        }
        val enabledDescriptor = descriptor.copy(autoLoginEnabled = true)
        if (secret == null) {
            deleteSecretMaterial()
            writeDescriptor(enabledDescriptor)
            return@withContext
        }

        writeEncryptedTemporary(enabledDescriptor, secret)
        try {
            writeDescriptor(enabledDescriptor)
            try {
                Files.move(
                    temporarySecretFile.toPath(),
                    secretFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporarySecretFile.toPath(),
                    secretFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } catch (error: Exception) {
            temporarySecretFile.delete()
            deleteSecretMaterial()
            runCatching { writeDescriptor(enabledDescriptor.copy(autoLoginEnabled = false)) }
            throw error
        }
    }

    override suspend fun clearSecret() {
        withContext(Dispatchers.IO) {
            deleteSecretMaterial()
            readDescriptor()?.let { writeDescriptor(it.copy(autoLoginEnabled = false)) }
        }
    }

    override suspend fun forget() = withContext(Dispatchers.IO) {
        forgetInternal()
    }

    private fun readDescriptor(): SavedConnectionDescriptor? =
        preferences.getString(KEY_DESCRIPTOR, null)?.let { encoded ->
            runCatching { json.decodeFromString<SavedConnectionDescriptor>(encoded) }.getOrNull()
        }

    private fun writeDescriptor(descriptor: SavedConnectionDescriptor) {
        val committed = preferences.edit()
            .putString(KEY_DESCRIPTOR, json.encodeToString(descriptor))
            .commit()
        if (!committed) throw IOException("Could not save connection metadata.")
    }

    private fun writeEncryptedTemporary(
        descriptor: SavedConnectionDescriptor,
        secret: ReusableSecret,
    ) {
        applicationContext.noBackupFilesDir.mkdirs()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(additionalData(applicationContext.packageName, descriptor))
        val ciphertext = cipher.doFinal(secret.value.toByteArray(StandardCharsets.UTF_8))
        val record = EncryptedSecretRecord(
            version = SavedConnectionDescriptor.CURRENT_VERSION,
            iv = Base64.getEncoder().encodeToString(cipher.iv),
            ciphertext = Base64.getEncoder().encodeToString(ciphertext),
        )
        FileOutputStream(temporarySecretFile).use { output ->
            output.write(json.encodeToString(record).toByteArray(StandardCharsets.UTF_8))
            output.fd.sync()
        }
    }

    private fun decrypt(descriptor: SavedConnectionDescriptor): ReusableSecret {
        val record = json.decodeFromString<EncryptedSecretRecord>(secretFile.readText())
        require(record.version == SavedConnectionDescriptor.CURRENT_VERSION) {
            "Unsupported encrypted connection version."
        }
        val key = keyStore().getKey(KEY_ALIAS, null) as? SecretKey
            ?: throw IOException("The saved connection key is unavailable.")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(GCM_TAG_BITS, Base64.getDecoder().decode(record.iv)),
        )
        cipher.updateAAD(additionalData(applicationContext.packageName, descriptor))
        val plaintext = cipher.doFinal(Base64.getDecoder().decode(record.ciphertext))
        return ReusableSecret(String(plaintext, StandardCharsets.UTF_8))
    }

    private fun getOrCreateKey(): SecretKey {
        val existing = keyStore().getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUnlockedDeviceRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun deleteSecretMaterial() {
        if (secretFile.exists() && !secretFile.delete()) {
            throw IOException("Could not remove encrypted connection material.")
        }
        temporarySecretFile.delete()
        val store = keyStore()
        if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS)
    }

    private fun forgetInternal() {
        val cleared = preferences.edit().clear().commit()
        deleteSecretMaterial()
        if (!cleared) throw IOException("Could not remove saved connection metadata.")
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    @Serializable
    private data class EncryptedSecretRecord(
        val version: Int,
        val iv: String,
        val ciphertext: String,
    )

    internal companion object {
        const val PREFERENCES_NAME = "celeste_connection"
        private const val KEY_DESCRIPTOR = "descriptor"
        private const val SECRET_FILE_NAME = "celeste_connection_secret_v1.json"
        private const val KEY_ALIAS = "celeste_connection_key_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128

        fun additionalData(
            applicationId: String,
            descriptor: SavedConnectionDescriptor,
        ): ByteArray = listOf(
            applicationId,
            descriptor.version.toString(),
            descriptor.baseUrl,
            descriptor.authMode.name,
        ).joinToString("|").toByteArray(StandardCharsets.UTF_8)
    }
}
