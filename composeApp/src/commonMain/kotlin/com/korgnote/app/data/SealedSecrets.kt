package com.korgnote.app.data

/**
 * Seals a secret — the paired server's token, the WebDAV password — with a key only this device
 * holds, and opens it again: on Android, a key in the Android Keystore, which never leaves it.
 *
 * What it buys is that settings copied off the device are no use: Android backs them up, to the
 * cloud and to the next phone, and the key goes with neither. Restored there, the secret cannot be
 * opened, and the app asks for it again — pairing again, or the password — which is what should
 * happen on another phone.
 */
interface SecretCipher {
    /** [plain], sealed; null when it cannot be, as when the key store is not working. */
    fun seal(plain: String): String?

    /** What [sealed] held; null when it cannot be opened, as when the key it was sealed with is gone. */
    fun open(sealed: String): String?
}

/** Marks a sealed secret, telling it apart from one saved in the clear before secrets were sealed. */
internal const val SEALED_PREFIX = "sealed1:"

/**
 * [plain] as settings keep it: sealed, or, when the key store fails, in the clear as before — a
 * pairing kept unsealed is better than a pairing lost.
 */
internal fun sealSecret(plain: String, cipher: SecretCipher): String =
    cipher.seal(plain)?.let { SEALED_PREFIX + it } ?: plain

/**
 * The secret [stored] holds, or null. One an older version saved in the clear is sealed in passing,
 * and given to [save] to replace it.
 */
internal fun openSecret(stored: String?, cipher: SecretCipher, save: (String) -> Unit): String? = when {
    stored == null -> null
    stored.startsWith(SEALED_PREFIX) -> cipher.open(stored.removePrefix(SEALED_PREFIX))
    else -> stored.also { sealSecret(it, cipher).takeIf { sealed -> sealed != it }?.let(save) }
}
