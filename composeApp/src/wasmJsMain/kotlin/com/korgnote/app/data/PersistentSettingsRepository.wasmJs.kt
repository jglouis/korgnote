package com.korgnote.app.data

import com.korgnote.common.SERVER_PORT
import kotlinx.browser.localStorage

/**
 * Settings kept in the page's `localStorage`, so pinned notes, saved searches and the rest of
 * Settings survive a reload.
 *
 * Host and port are not stored: the web app is served by the server it talks to, so they always
 * come from the page origin, and a stored pair would outlive a move to another address. Storage
 * can be unavailable (blocked site data, some private windows); the app then works as before,
 * forgetting on reload.
 */
class PersistentSettingsRepository : KeyValueSettings() {
    private var host = defaultServerBaseUrl().removePrefix("http://").removePrefix("https://").substringBefore(":")
    private var port = defaultServerBaseUrl().substringAfterLast(':').toIntOrNull() ?: SERVER_PORT
    private var pairedServer: PairedServer? = PairedServer.decode(read(KEY_PAIRED_SERVER))


    override fun getServerHost(): String = host
    override fun getServerPort(): Int = port
    override fun getPairedServer(): PairedServer? = pairedServer

    override fun setServerHost(host: String) {
        this.host = host
    }

    override fun setServerPort(port: Int) {
        this.port = port
    }

    override fun setPairedServer(server: PairedServer?) {
        pairedServer = server
        write(KEY_PAIRED_SERVER, server?.encode().orEmpty())
        // The shared token from before pairing, which nothing reads any more.
        write(KEY_TOKEN, "")
    }

    // A browser has no folder of notes to offer: the server this page comes from is the only source.
    override fun getNotesSource(): NotesSource = NotesSource.Server

    override fun setNotesSource(source: NotesSource) {}

    override fun read(key: String): String? = runCatching { localStorage.getItem(PREFIX + key) }.getOrNull()

    override fun write(key: String, value: String) {
        runCatching { localStorage.setItem(PREFIX + key, value) }
    }

    companion object {
        private const val PREFIX = "korgnote."
        private const val KEY_TOKEN = "server_token"
        private const val KEY_PAIRED_SERVER = "paired_server"
    }
}
