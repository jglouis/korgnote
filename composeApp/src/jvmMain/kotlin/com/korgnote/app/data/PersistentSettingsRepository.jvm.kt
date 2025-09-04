package com.korgnote.app.data

import com.korgnote.common.SERVER_PORT
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.prefs.Preferences

class PersistentSettingsRepository : SettingsRepository {
    private val prefs: Preferences = Preferences.userRoot().node("com.korgnote.app")

    private val hostFlow = MutableStateFlow(loadHost())
    private val portFlow = MutableStateFlow(loadPort())

    override val serverHostFlow: Flow<String> = hostFlow
    override val serverPortFlow: Flow<Int> = portFlow

    override fun getServerHost(): String = hostFlow.value
    override fun getServerPort(): Int = portFlow.value

    override fun setServerHost(host: String) {
        hostFlow.value = host
        prefs.put(KEY_HOST, host)
    }

    override fun setServerPort(port: Int) {
        portFlow.value = port
        prefs.putInt(KEY_PORT, port)
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
    }
}
