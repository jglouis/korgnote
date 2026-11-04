package com.korgnote.app.data

import com.korgnote.common.SERVER_PORT
import platform.Foundation.NSUserDefaults

class PersistentSettingsRepository : KeyValueSettings() {
    private val defaults = NSUserDefaults.standardUserDefaults()

    init {
        // The shared token from before pairing, which nothing reads: a secret in the clear, and in
        // every backup, for nothing.
        defaults.removeObjectForKey(KEY_TOKEN)
    }

    private var host = loadHost()
    private var port = loadPort()
    private var pairedServer: PairedServer? = PairedServer.decode(readSecret(KEY_PAIRED_SERVER))

    override fun read(key: String): String? = defaults.stringForKey(key)

    override fun write(key: String, value: String) = defaults.setObject(value, forKey = key)

    /**
     * The secret kept at [key], from the Keychain. One an older version kept in the user defaults is
     * moved there on the way — and only taken out of them once the Keychain has it.
     */
    private fun readSecret(key: String): String? {
        Keychain.read(key)?.let { return it }
        val legacy = defaults.stringForKey(key) ?: return null
        if (Keychain.write(key, legacy)) defaults.removeObjectForKey(key)
        return legacy
    }

    /** Keeps [value] in the Keychain; in the user defaults, as before, only if the Keychain refuses it. */
    private fun writeSecret(key: String, value: String?) {
        if (Keychain.write(key, value)) defaults.removeObjectForKey(key)
        else if (value == null) defaults.removeObjectForKey(key)
        else defaults.setObject(value, forKey = key)
    }


    override fun getServerHost(): String = host
    override fun getServerPort(): Int = port
    override fun getPairedServer(): PairedServer? = pairedServer

    override fun setServerHost(host: String) {
        this.host = host
        defaults.setObject(host, forKey = KEY_HOST)
    }

    override fun setServerPort(port: Int) {
        this.port = port
        defaults.setInteger(port.toLong(), forKey = KEY_PORT)
    }

    override fun setPairedServer(server: PairedServer?) {
        pairedServer = server
        writeSecret(KEY_PAIRED_SERVER, server?.encode())
    }

    // Read once and kept, as the paired server is: every WebDAV request reads it.
    private var webDavAccount: WebDavAccount? = WebDavAccount.decode(readSecret(KEY_WEBDAV_ACCOUNT))

    override fun getWebDavAccount(): WebDavAccount? = webDavAccount

    override fun setWebDavAccount(account: WebDavAccount?) {
        webDavAccount = account
        writeSecret(KEY_WEBDAV_ACCOUNT, account?.encode())
    }

    private fun loadHost(): String {
        val def = defaultServerBaseUrl().removePrefix("http://").removePrefix("https://").substringBefore(":")
        val value = defaults.stringForKey(KEY_HOST)
        return value ?: def
    }

    private fun loadPort(): Int {
        val stored = defaults.objectForKey(KEY_PORT)
        return when (stored) {
            is Long -> stored.toInt()
            is Int -> stored
            else -> SERVER_PORT
        }
    }

    companion object {
        private const val KEY_HOST = "server_host"
        private const val KEY_PORT = "server_port"
        private const val KEY_TOKEN = "server_token"
        private const val KEY_PAIRED_SERVER = "paired_server"
        private const val KEY_WEBDAV_ACCOUNT = "webdav_account"
    }
}
