package au.com.elied.vitalsignal.phone.data.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Explicit Android Keystore boundary for non-exportable AES keys.
 *
 * Loading and first-time creation are intentionally separate. If an expected key disappears or is
 * invalidated, [loadExisting] returns a recovery state; it never creates a replacement and never
 * makes old encrypted health records look empty. Background ingestion keys do not require a
 * biometric prompt. StrongBox is opportunistic because it is not available on every device.
 */
class AndroidKeystoreAesKeyProvider(
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) },
) {
    fun loadExisting(alias: String, keyId: String): StorageKeyAccess {
        validateIdentity(alias, keyId)
        return try {
            val key = keyStore.getKey(alias, null)
                ?: return StorageKeyAccess.RecoveryRequired(keyId, StorageKeyFailure.MISSING)
            if (key !is SecretKey || !key.algorithm.equals(KeyProperties.KEY_ALGORITHM_AES, true)) {
                StorageKeyAccess.RecoveryRequired(keyId, StorageKeyFailure.INVALID_TYPE)
            } else {
                StorageKeyAccess.Ready(keyId, key, createdWithStrongBoxPreference = null)
            }
        } catch (_: Exception) {
            StorageKeyAccess.RecoveryRequired(keyId, StorageKeyFailure.UNAVAILABLE)
        }
    }

    /** Call only during an explicit, empty-vault pilot initialisation flow. */
    fun initialiseFresh(alias: String, keyId: String, preferStrongBox: Boolean): StorageKeyAccess {
        validateIdentity(alias, keyId)
        if (keyStore.containsAlias(alias)) return loadExisting(alias, keyId)
        return try {
            val key = generate(alias, useStrongBox = preferStrongBox)
            StorageKeyAccess.Ready(keyId, key, createdWithStrongBoxPreference = preferStrongBox)
        } catch (_: StrongBoxUnavailableException) {
            try {
                val fallback = generate(alias, useStrongBox = false)
                StorageKeyAccess.Ready(keyId, fallback, createdWithStrongBoxPreference = false)
            } catch (_: Exception) {
                StorageKeyAccess.RecoveryRequired(keyId, StorageKeyFailure.GENERATION_FAILED)
            }
        } catch (_: Exception) {
            StorageKeyAccess.RecoveryRequired(keyId, StorageKeyFailure.GENERATION_FAILED)
        }
    }

    private fun generate(alias: String, useStrongBox: Boolean): SecretKey {
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
        if (useStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(true)
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(builder.build())
            generateKey()
        }
    }

    private fun validateIdentity(alias: String, keyId: String) {
        require(alias.matches(SAFE_ID)) { "Invalid Keystore alias" }
        require(keyId.matches(SAFE_ID)) { "Invalid storage key ID" }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        val SAFE_ID = Regex("[A-Za-z0-9._:-]{1,96}")
    }
}

sealed interface StorageKeyAccess {
    data class Ready(
        val keyId: String,
        val secretKey: SecretKey,
        /** Null means an existing key was loaded and its backing was not inferred. */
        val createdWithStrongBoxPreference: Boolean?,
    ) : StorageKeyAccess

    data class RecoveryRequired(
        val keyId: String,
        val failure: StorageKeyFailure,
    ) : StorageKeyAccess
}

enum class StorageKeyFailure { MISSING, INVALID_TYPE, UNAVAILABLE, GENERATION_FAILED }

object VitalSignalKeyAliases {
    const val PHONE_STORAGE = "vitalsignal.phone.storage.kek.v1"
    const val PHONE_TRANSPORT_RECEIVER = "vitalsignal.phone.transport.receiver.v1"
    const val WATCH_OUTBOX_STORAGE = "vitalsignal.watch.outbox.kek.v1"
    const val WATCH_ACK_AUTHENTICATION = "vitalsignal.watch.ack.authentication.v1"
}
