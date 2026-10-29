package com.korgnote.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Secrets sealed in settings: kept so, moved there from the clear, and lost with their key. */
class SealedSecretsTest {

    /** Reverses the text, as a stand-in for a key store; [working] false is one that fails. */
    private class FakeCipher(var working: Boolean = true) : SecretCipher {
        val keys = mutableSetOf("k1")
        var key = "k1"
        override fun seal(plain: String) = if (working) "$key:${plain.reversed()}" else null
        override fun open(sealed: String) = sealed.substringBefore(':').takeIf { it in keys }?.let { sealed.substringAfter(':').reversed() }
    }

    @Test
    fun aSecretIsKeptSealedAndOpenedAgain() {
        val cipher = FakeCipher()
        val stored = sealSecret("token", cipher)
        assertTrue(stored.startsWith(SEALED_PREFIX) && "token" !in stored, stored)
        assertEquals("token", openSecret(stored, cipher) { error("nothing to move") })
    }

    @Test
    fun oneKeptInTheClearByAnOlderVersionIsSealedOnTheWay() {
        val cipher = FakeCipher()
        var saved: String? = null
        assertEquals("token", openSecret("token", cipher) { saved = it })
        assertEquals("token", openSecret(saved, cipher) { error("already sealed") })
        assertTrue(saved!!.startsWith(SEALED_PREFIX))
    }

    @Test
    fun oneSealedWithAKeyThisDeviceHasNotIsGone() {
        // As on a phone restored from a backup: the settings came, the key did not.
        val cipher = FakeCipher()
        val stored = sealSecret("token", cipher)
        cipher.keys.clear()
        assertNull(openSecret(stored, cipher) { error("nothing to move") })
    }

    @Test
    fun aKeyStoreThatFailsKeepsTheSecretAsBefore() {
        val cipher = FakeCipher(working = false)
        assertEquals("token", sealSecret("token", cipher))
        assertEquals("token", openSecret("token", cipher) { error("could not be sealed, so not moved") })
    }

    @Test
    fun nothingKeptIsNothing() {
        assertNull(openSecret(null, FakeCipher()) { error("nothing to move") })
    }
}
