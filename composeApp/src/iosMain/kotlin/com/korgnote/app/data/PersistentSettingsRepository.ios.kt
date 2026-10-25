package com.korgnote.app.data

import com.korgnote.app.files.FileLinkMapping
import com.korgnote.app.files.decodeFileLinkMappings
import com.korgnote.app.files.encodeFileLinkMappings
import com.korgnote.app.notifications.ReminderSettings
import com.korgnote.common.SERVER_PORT
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import platform.Foundation.NSUserDefaults

class PersistentSettingsRepository : SettingsRepository {
    private val defaults = NSUserDefaults.standardUserDefaults()

    private val hostFlow = MutableStateFlow(loadHost())
    private val portFlow = MutableStateFlow(loadPort())
    private val tokenFlow = MutableStateFlow(defaults.stringForKey(KEY_TOKEN).orEmpty())

    override val serverHostFlow: Flow<String> = hostFlow
    override val serverPortFlow: Flow<Int> = portFlow
    override val serverTokenFlow: Flow<String> = tokenFlow

    override fun getServerHost(): String = hostFlow.value
    override fun getServerPort(): Int = portFlow.value
    override fun getServerToken(): String = tokenFlow.value

    override fun setServerHost(host: String) {
        hostFlow.value = host
        defaults.setObject(host, forKey = KEY_HOST)
    }

    override fun setServerPort(port: Int) {
        portFlow.value = port
        defaults.setInteger(port.toLong(), forKey = KEY_PORT)
    }

    override fun setServerToken(token: String) {
        tokenFlow.value = token
        defaults.setObject(token, forKey = KEY_TOKEN)
    }

    override fun getReminderSettings(): ReminderSettings = ReminderSettings.decode(defaults.stringForKey(KEY_REMINDERS))

    override fun setReminderSettings(settings: ReminderSettings) {
        defaults.setObject(settings.encode(), forKey = KEY_REMINDERS)
    }

    override fun getNewNotesFolder(): String = defaults.stringForKey(KEY_NEW_NOTES_FOLDER) ?: ""

    override fun setNewNotesFolder(folder: String) {
        defaults.setObject(folder, forKey = KEY_NEW_NOTES_FOLDER)
    }

    override fun getPinnedNotes(): List<String> = decodePinnedNotes(defaults.stringForKey(KEY_PINNED_NOTES))

    override fun setPinnedNotes(paths: List<String>) {
        defaults.setObject(encodePinnedNotes(paths), forKey = KEY_PINNED_NOTES)
    }

    override fun getFileLinkMappings(): List<FileLinkMapping> =
        decodeFileLinkMappings(defaults.stringForKey(KEY_FILE_LINK_MAPPINGS))

    override fun setFileLinkMappings(mappings: List<FileLinkMapping>) {
        defaults.setObject(encodeFileLinkMappings(mappings), forKey = KEY_FILE_LINK_MAPPINGS)
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
        private const val KEY_REMINDERS = "reminders"
        private const val KEY_NEW_NOTES_FOLDER = "new_notes_folder"
        private const val KEY_PINNED_NOTES = "pinned_notes"
        private const val KEY_FILE_LINK_MAPPINGS = "file_link_mappings"
    }
}
