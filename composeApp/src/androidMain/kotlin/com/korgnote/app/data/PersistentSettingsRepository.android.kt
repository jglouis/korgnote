package com.korgnote.app.data

import android.content.Context
import android.content.SharedPreferences
import com.korgnote.common.SERVER_PORT
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.core.content.edit

class PersistentSettingsRepository(appContext: Context) : SettingsRepository {
    private val prefs: SharedPreferences = appContext.getSharedPreferences("korgnote_settings", Context.MODE_PRIVATE)

    private val hostFlow = MutableStateFlow(loadHost())
    private val portFlow = MutableStateFlow(loadPort())
    private val tokenFlow = MutableStateFlow(prefs.getString(KEY_TOKEN, "").orEmpty())

    override val serverHostFlow: Flow<String> = hostFlow
    override val serverPortFlow: Flow<Int> = portFlow
    override val serverTokenFlow: Flow<String> = tokenFlow

    override fun getServerHost(): String = hostFlow.value
    override fun getServerPort(): Int = portFlow.value
    override fun getServerToken(): String = tokenFlow.value

    override fun setServerHost(host: String) {
        hostFlow.value = host
        prefs.edit { putString(KEY_HOST, host) }
    }

    override fun setServerPort(port: Int) {
        portFlow.value = port
        prefs.edit { putInt(KEY_PORT, port) }
    }

    override fun setServerToken(token: String) {
        tokenFlow.value = token
        prefs.edit { putString(KEY_TOKEN, token) }
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
    }
}
