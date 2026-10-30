package com.korgnote.app.data

import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A desktop paired with a server on another computer keeps the token that lets it read and write
 * every note there. In Java's Preferences — on a Mac a plist anyone's processes can read, copied
 * into every backup — it must be sealed, as on a phone, with a key the system's keyring holds.
 */
class DesktopSecretsTest {

    private val node: Preferences = Preferences.userRoot().node("com.korgnote.app.test-${UUID.randomUUID()}")

    /** A keyring that lives in this test, never the one of whoever runs it. */
    private class TestKeyring : Keyring {
        val entries = mutableMapOf<String, String>()
        override fun read(name: String): String? = entries[name]
        override fun write(name: String, value: String): Boolean {
            entries[name] = value
            return true
        }
    }

    private val keyring = TestKeyring()
    private val token = "a1b2c3-the-device-token"
    private val paired = PairedServer(
        serverId = "server", serverName = "Home", addresses = listOf("192.168.1.10"), port = 8443,
        fingerprint = "ab:cd", deviceId = "device", token = token,
    )

    @AfterTest
    fun cleanUp() = node.removeNode()

    @Test
    fun thePairingTokenIsNotKeptInTheClear() {
        PersistentSettingsRepository(node, KeyringCipher(keyring)).setPairedServer(paired)

        val stored = node.get("paired_server", "")
        assertTrue(stored.isNotEmpty())
        assertFalse(token in stored, "the token is in the clear: $stored")
        assertEquals(paired, PersistentSettingsRepository(node, KeyringCipher(keyring)).getPairedServer())
    }

    /** Kept in the clear by an older version: sealed the first time it is read. */
    @Test
    fun aTokenKeptInTheClearIsSealedOnTheWay() {
        node.put("paired_server", paired.encode())

        assertEquals(paired, PersistentSettingsRepository(node, KeyringCipher(keyring)).getPairedServer())
        assertFalse(token in node.get("paired_server", ""))
    }

    /** Copied to another computer, the settings are no use: the key stayed in this one's keyring. */
    @Test
    fun withoutItsKeyTheTokenCannotBeOpened() {
        PersistentSettingsRepository(node, KeyringCipher(keyring)).setPairedServer(paired)

        assertNull(PersistentSettingsRepository(node, KeyringCipher(TestKeyring())).getPairedServer())
    }

    /** No keyring to be had, as on a Linux desktop without a secret service: kept as before. */
    @Test
    fun withoutAKeyringTheTokenIsKeptAsBefore() {
        val none = object : Keyring {
            override fun read(name: String): String? = null
            override fun write(name: String, value: String) = false
        }
        PersistentSettingsRepository(node, KeyringCipher(none)).setPairedServer(paired)

        assertEquals(paired, PersistentSettingsRepository(node, KeyringCipher(none)).getPairedServer())
    }
}
