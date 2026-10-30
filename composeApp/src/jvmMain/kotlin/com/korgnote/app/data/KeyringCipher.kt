package com.korgnote.app.data

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Where the desktop keeps a secret of its own: the system's keyring, or a stand-in for tests. */
interface Keyring {
    /** What is kept under [name]; null when nothing is, or the keyring cannot be reached. */
    fun read(name: String): String?

    /** Keeps [value] under [name]; false when the keyring cannot be reached. */
    fun write(name: String, value: String): Boolean
}

/**
 * The desktop's [SecretCipher]: AES-GCM with a key made on first use and kept in the system's
 * keyring, never in the settings. Java's Preferences are a file anyone's processes can read — on a
 * Mac a plist, in every backup — so the pairing token there is sealed, as on a phone.
 *
 * With no keyring to be had, as on a Linux desktop without a secret service, [seal] gives null and
 * the token is kept in the clear, as before: a pairing kept is better than a pairing lost.
 */
class KeyringCipher(private val keyring: Keyring) : SecretCipher {

    override fun seal(plain: String): String? = runCatching {
        val key = key(create = true) ?: return null
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv)) }
        Base64.getEncoder().encodeToString(iv + cipher.doFinal(plain.encodeToByteArray()))
    }.getOrNull()

    override fun open(sealed: String): String? = runCatching {
        val key = key(create = false) ?: return null
        val bytes = Base64.getDecoder().decode(sealed)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
        }
        cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES).decodeToString()
    }.getOrNull()

    /** The key in the keyring; made and kept there first when [create] and there is none yet. */
    private fun key(create: Boolean): SecretKeySpec? {
        val kept = keyring.read(KEY_NAME)
        val encoded = when {
            kept != null -> kept
            !create -> return null
            else -> Base64.getEncoder().encodeToString(ByteArray(KEY_BYTES).also(random::nextBytes))
                .takeIf { keyring.write(KEY_NAME, it) } ?: return null
        }
        return SecretKeySpec(Base64.getDecoder().decode(encoded), "AES")
    }

    private companion object {
        const val KEY_NAME = "settings-key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BYTES = 32
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        val random = SecureRandom()
    }
}

/** The system's keyring: macOS Keychain, Windows Credential Manager, or Secret Service on Linux. */
class OsKeyring : Keyring {
    private val keyring by lazy { runCatching { com.github.javakeyring.Keyring.create() }.getOrNull() }

    override fun read(name: String): String? = runCatching { keyring?.getPassword(SERVICE, name) }.getOrNull()

    override fun write(name: String, value: String): Boolean = runCatching {
        val keyring = keyring ?: return false
        keyring.setPassword(SERVICE, name, value)
        true
    }.getOrDefault(false)

    private companion object {
        const val SERVICE = "KorgNote"
    }
}
