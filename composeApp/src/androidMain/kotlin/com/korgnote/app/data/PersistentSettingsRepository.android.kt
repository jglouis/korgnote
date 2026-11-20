package com.korgnote.app.data

import android.content.Context
import android.content.SharedPreferences
import com.korgnote.common.SERVER_PORT
import androidx.core.content.edit

class PersistentSettingsRepository(
    appContext: Context,
    /** Seals the paired server's token and the WebDAV password; see [SecretCipher]. */
    private val cipher: SecretCipher = KeystoreCipher(),
) : KeyValueSettings() {
    private val prefs: SharedPreferences = appContext.getSharedPreferences("korgnote_settings", Context.MODE_PRIVATE)

    /**
     * Where a secret goes when the Keystore will not seal it: in the clear, but in a file kept out
     * of every backup and every move to a new phone (`backup_rules.xml`, `data_extraction_rules.xml`),
     * as the settings above are not. A pairing kept so is better than a pairing lost, and it stays
     * on this phone.
     */
    private val deviceOnly: SharedPreferences = appContext.getSharedPreferences(DEVICE_ONLY_PREFS, Context.MODE_PRIVATE)

    init {
        // The shared token from before pairing, which nothing reads: a secret in the clear, and in
        // every backup, for nothing.
        if (prefs.contains(KEY_TOKEN)) prefs.edit { remove(KEY_TOKEN) }
    }

    private var host = loadHost()
    private var port = loadPort()
    private var pairedServer: PairedServer? = PairedServer.decode(readSecret(KEY_PAIRED_SERVER))

    override fun read(key: String): String? = prefs.getString(key, null)

    override fun write(key: String, value: String) = prefs.edit { putString(key, value) }

    /**
     * The secret kept at [key], opened. One an older version kept in the clear in the settings, which
     * are backed up, is sealed on the way, or moved to [deviceOnly] when it cannot be.
     */
    private fun readSecret(key: String): String? {
        val stored = prefs.getString(key, null)
        if (stored != null) {
            val opened = openSecret(stored, cipher) { sealed -> prefs.edit { putString(key, sealed) } }
            if (opened != null && !stored.startsWith(SEALED_PREFIX) && prefs.getString(key, null) == stored) writeSecret(key, opened)
            return opened
        }
        return deviceOnly.getString(key, null)?.also { plain ->
            // The Keystore may work again: sealed, it can go back with the rest.
            sealSecret(plain, cipher)?.let { sealed ->
                prefs.edit { putString(key, sealed) }
                deviceOnly.edit { remove(key) }
            }
        }
    }

    /** Keeps [value] sealed in the settings; in [deviceOnly] when the Keystore will not seal it. */
    private fun writeSecret(key: String, value: String?) {
        val sealed = value?.let { sealSecret(it, cipher) }
        prefs.edit { if (sealed == null) remove(key) else putString(key, sealed) }
        deviceOnly.edit { if (value == null || sealed != null) remove(key) else putString(key, value) }
    }


    override fun getServerHost(): String = host
    override fun getServerPort(): Int = port
    override fun getPairedServer(): PairedServer? = pairedServer

    override fun setServerHost(host: String) {
        this.host = host
        prefs.edit { putString(KEY_HOST, host) }
    }

    override fun setServerPort(port: Int) {
        this.port = port
        prefs.edit { putInt(KEY_PORT, port) }
    }

    override fun setPairedServer(server: PairedServer?) {
        pairedServer = server
        writeSecret(KEY_PAIRED_SERVER, server?.encode())
    }

    // Opened once and kept, as the paired server is: every WebDAV request reads it.
    private var webDavAccount: WebDavAccount? = WebDavAccount.decode(readSecret(KEY_WEBDAV_ACCOUNT))

    override fun getWebDavAccount(): WebDavAccount? = webDavAccount

    override fun setWebDavAccount(account: WebDavAccount?) {
        webDavAccount = account
        writeSecret(KEY_WEBDAV_ACCOUNT, account?.encode())
    }

    private fun loadHost(): String {
        val def = defaultServerBaseUrl()
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore(":")
        return prefs.getString(KEY_HOST, def) ?: def
    }

    private fun loadPort(): Int {
        return prefs.getInt(KEY_PORT, SERVER_PORT)
    }

    companion object {
        private const val KEY_HOST = "server_host"
        private const val KEY_PORT = "server_port"
        private const val KEY_TOKEN = "server_token"
        private const val KEY_PAIRED_SERVER = "paired_server"
        private const val KEY_WEBDAV_ACCOUNT = "webdav_account"

        /** Kept out of every backup: see [deviceOnly]. */
        const val DEVICE_ONLY_PREFS = "korgnote_device_only"
    }
}
