package com.korgnote.app.data

import com.korgnote.common.SERVER_PORT
import java.util.prefs.Preferences

class PersistentSettingsRepository(
    private val prefs: Preferences = Preferences.userRoot().node("com.korgnote.app"),
    /** Seals the paired server's token with a key the system's keyring holds; see [KeyringCipher]. */
    private val cipher: SecretCipher = KeyringCipher(OsKeyring()),
) : KeyValueSettings() {

    private var host = loadHost()
    private var port = loadPort()
    private val storedPairing: String? = prefs.get(KEY_PAIRED_SERVER, null)
    private var pairedServer: PairedServer? = PairedServer.decode(
        // One an older version kept in the clear is sealed on the way.
        openSecret(storedPairing, cipher) { sealed -> prefs.put(KEY_PAIRED_SERVER, sealed) },
    )
    private var pairingUnreadable = storedPairing != null && pairedServer == null

    init {
        if (pairingUnreadable) println("[DEBUG_LOG][Settings] The pairing is kept but cannot be opened: the keyring did not give its key. Not syncing until it is paired again.")
    }

    override fun isPairingUnreadable(): Boolean = pairingUnreadable


    override fun read(key: String): String? = prefs.get(key, null)

    override fun write(key: String, value: String) = prefs.put(key, value)

    override fun getServerHost(): String = host
    override fun getServerPort(): Int = port
    override fun getPairedServer(): PairedServer? = pairedServer

    override fun setServerHost(host: String) {
        this.host = host
        prefs.put(KEY_HOST, host)
    }

    override fun setServerPort(port: Int) {
        this.port = port
        prefs.putInt(KEY_PORT, port)
    }

    override fun setPairedServer(server: PairedServer?) {
        pairedServer = server
        pairingUnreadable = false
        // With no keyring to be had the token is kept in the clear, as before: Java's preferences
        // are this user's own, and go to no phone backup. See [KeyringCipher].
        if (server == null) prefs.remove(KEY_PAIRED_SERVER) else prefs.put(KEY_PAIRED_SERVER, server.encode().let { sealSecret(it, cipher) ?: it })
        // The shared token from before pairing, which nothing reads any more.
        prefs.remove(KEY_TOKEN)
    }

    override fun setReadingPlaces(places: List<ReadingPlace>) {
        // A stored value may be no longer than Preferences allows: the oldest places go first.
        var kept = places
        while (encodeReadingPlaces(kept).length > Preferences.MAX_VALUE_LENGTH && kept.isNotEmpty()) kept = kept.dropLast(1)
        super.setReadingPlaces(kept)
    }

    private fun loadHost(): String {
        val def = defaultServerBaseUrl().removePrefix("http://").removePrefix("https://").substringBefore(":")
        return prefs.get(KEY_HOST, def)
    }

    private fun loadPort(): Int {
        return prefs.getInt(KEY_PORT, SERVER_PORT)
    }

    companion object {
        private const val KEY_HOST = "server_host"
        private const val KEY_PORT = "server_port"
        private const val KEY_TOKEN = "server_token"
        private const val KEY_PAIRED_SERVER = "paired_server"
    }
}
