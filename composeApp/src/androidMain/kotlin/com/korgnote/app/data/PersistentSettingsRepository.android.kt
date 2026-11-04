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

    /** The secret kept at [key], opened; one an older version kept in the clear is sealed on the way. */
    private fun readSecret(key: String): String? =
        openSecret(prefs.getString(key, null), cipher) { sealed -> prefs.edit { putString(key, sealed) } }

    private fun SharedPreferences.Editor.putSecret(key: String, value: String?) {
        if (value == null) remove(key) else putString(key, sealSecret(value, cipher))
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
        prefs.edit { putSecret(KEY_PAIRED_SERVER, server?.encode()) }
    }

    // Opened once and kept, as the paired server is: every WebDAV request reads it.
    private var webDavAccount: WebDavAccount? = WebDavAccount.decode(readSecret(KEY_WEBDAV_ACCOUNT))

    override fun getWebDavAccount(): WebDavAccount? = webDavAccount

    override fun setWebDavAccount(account: WebDavAccount?) {
        webDavAccount = account
        prefs.edit { putSecret(KEY_WEBDAV_ACCOUNT, account?.encode()) }
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
    }
}
