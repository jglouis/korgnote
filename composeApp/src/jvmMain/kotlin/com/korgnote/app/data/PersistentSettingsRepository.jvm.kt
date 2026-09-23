package com.korgnote.app.data

import com.korgnote.app.notifications.ReminderSettings
import com.korgnote.common.SERVER_PORT
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.prefs.Preferences

class PersistentSettingsRepository : SettingsRepository {
    private val prefs: Preferences = Preferences.userRoot().node("com.korgnote.app")

    private val hostFlow = MutableStateFlow(loadHost())
    private val portFlow = MutableStateFlow(loadPort())
    private val tokenFlow = MutableStateFlow(prefs.get(KEY_TOKEN, ""))

    override val serverHostFlow: Flow<String> = hostFlow
    override val serverPortFlow: Flow<Int> = portFlow
    override val serverTokenFlow: Flow<String> = tokenFlow

    override fun getServerHost(): String = hostFlow.value
    override fun getServerPort(): Int = portFlow.value
    override fun getServerToken(): String = tokenFlow.value

    override fun setServerHost(host: String) {
        hostFlow.value = host
        prefs.put(KEY_HOST, host)
    }

    override fun setServerPort(port: Int) {
        portFlow.value = port
        prefs.putInt(KEY_PORT, port)
    }

    override fun setServerToken(token: String) {
        tokenFlow.value = token
        prefs.put(KEY_TOKEN, token)
    }

    override fun getReminderSettings(): ReminderSettings = ReminderSettings.decode(prefs.get(KEY_REMINDERS, null))

    override fun setReminderSettings(settings: ReminderSettings) {
        prefs.put(KEY_REMINDERS, settings.encode())
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
        private const val KEY_REMINDERS = "reminders"
    }
}
