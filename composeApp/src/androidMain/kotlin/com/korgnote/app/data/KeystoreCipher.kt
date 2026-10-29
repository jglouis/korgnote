package com.korgnote.app.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals secrets with an AES key in the Android Keystore, made on first use and never exportable,
 * so it is in no backup. The key needs no unlocking: the widget and the sync in the background
 * read the secrets too.
 *
 * A sealed secret is the GCM nonce followed by the ciphertext and its tag, in Base64.
 */
internal class KeystoreCipher(private val alias: String = "korgnote-settings") : SecretCipher {

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEY_STORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    override fun seal(plain: String): String? = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        Base64.encodeToString(cipher.iv + cipher.doFinal(plain.encodeToByteArray()), Base64.NO_WRAP)
    } catch (e: Exception) {
        Log.w("KeystoreCipher", "Cannot seal a secret: ${e::class.simpleName} ${e.message.orEmpty()}")
        null
    }

    override fun open(sealed: String): String? = try {
        val bytes = Base64.decode(sealed, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, NONCE_BYTES))
        cipher.doFinal(bytes, NONCE_BYTES, bytes.size - NONCE_BYTES).decodeToString()
    } catch (e: Exception) {
        // Sealed with a key this device does not have, as after a restore: the secret is gone.
        Log.w("KeystoreCipher", "Cannot open a secret: ${e::class.simpleName} ${e.message.orEmpty()}")
        null
    }

    private companion object {
        const val KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
    }
}
