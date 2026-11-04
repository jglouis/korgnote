package com.korgnote.app.data

import com.korgnote.common.SERVER_PORT

class InMemorySettingsRepository(
    initialHost: String = defaultServerBaseUrl()
        .removePrefix("http://")
        .removePrefix("https://")
        .substringBefore(":"),
    initialPort: Int = SERVER_PORT,
    initialPairedServer: PairedServer? = null,
    /** The server unless told otherwise, so a test sees the notes rather than the welcome screens. */
    initialNotesSource: NotesSource? = NotesSource.Server,
) : KeyValueSettings() {
    private val kept = mutableMapOf<String, String>()

    init {
        initialNotesSource?.let { setNotesSource(it) }
    }

    override fun read(key: String): String? = kept[key]

    override fun write(key: String, value: String) {
        kept[key] = value
    }

    private var host = initialHost
    private var port = initialPort
    private var pairedServer = initialPairedServer

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
    }

    private var webDavAccount: WebDavAccount? = null
    override fun getWebDavAccount(): WebDavAccount? = webDavAccount
    override fun setWebDavAccount(account: WebDavAccount?) {
        webDavAccount = account
    }
}
