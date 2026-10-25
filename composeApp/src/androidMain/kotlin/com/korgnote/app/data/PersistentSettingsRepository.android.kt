package com.korgnote.app.data

import android.content.Context
import android.content.SharedPreferences
import com.korgnote.app.files.FileLinkMapping
import com.korgnote.app.files.decodeFileLinkMappings
import com.korgnote.app.files.encodeFileLinkMappings
import com.korgnote.app.notifications.ReminderSettings
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

    override fun getReminderSettings(): ReminderSettings = ReminderSettings.decode(prefs.getString(KEY_REMINDERS, null))

    override fun setReminderSettings(settings: ReminderSettings) {
        prefs.edit { putString(KEY_REMINDERS, settings.encode()) }
    }

    override fun getNewNotesFolder(): String = prefs.getString(KEY_NEW_NOTES_FOLDER, "") ?: ""

    override fun setNewNotesFolder(folder: String) {
        prefs.edit { putString(KEY_NEW_NOTES_FOLDER, folder) }
    }

    override fun getPinnedNotes(): List<String> = decodePinnedNotes(prefs.getString(KEY_PINNED_NOTES, null))

    override fun setPinnedNotes(paths: List<String>) {
        prefs.edit { putString(KEY_PINNED_NOTES, encodePinnedNotes(paths)) }
    }

    override fun getFileLinkMappings(): List<FileLinkMapping> =
        decodeFileLinkMappings(prefs.getString(KEY_FILE_LINK_MAPPINGS, null))

    override fun setFileLinkMappings(mappings: List<FileLinkMapping>) {
        prefs.edit { putString(KEY_FILE_LINK_MAPPINGS, encodeFileLinkMappings(mappings)) }
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
        private const val KEY_NEW_NOTES_FOLDER = "new_notes_folder"
        private const val KEY_PORT = "server_port"
        private const val KEY_TOKEN = "server_token"
        private const val KEY_REMINDERS = "reminders"
        private const val KEY_PINNED_NOTES = "pinned_notes"
        private const val KEY_FILE_LINK_MAPPINGS = "file_link_mappings"
    }
}
