package com.korgnote.app.data

import com.korgnote.app.files.FileLinkMapping
import com.korgnote.app.files.decodeFileLinkMappings
import com.korgnote.app.files.encodeFileLinkMappings
import com.korgnote.app.notifications.ReminderSettings
import com.korgnote.common.SERVER_PORT
import kotlinx.coroutines.flow.MutableStateFlow
import platform.Foundation.NSUserDefaults

class PersistentSettingsRepository : SettingsRepository {
    private val defaults = NSUserDefaults.standardUserDefaults()

    private val hostFlow = MutableStateFlow(loadHost())
    private val portFlow = MutableStateFlow(loadPort())
    private var pairedServer: PairedServer? = PairedServer.decode(defaults.stringForKey(KEY_PAIRED_SERVER))


    override fun getServerHost(): String = hostFlow.value
    override fun getServerPort(): Int = portFlow.value
    override fun getPairedServer(): PairedServer? = pairedServer

    override fun setServerHost(host: String) {
        hostFlow.value = host
        defaults.setObject(host, forKey = KEY_HOST)
    }

    override fun setServerPort(port: Int) {
        portFlow.value = port
        defaults.setInteger(port.toLong(), forKey = KEY_PORT)
    }

    override fun setPairedServer(server: PairedServer?) {
        pairedServer = server
        if (server == null) defaults.removeObjectForKey(KEY_PAIRED_SERVER) else defaults.setObject(server.encode(), forKey = KEY_PAIRED_SERVER)
        // The shared token from before pairing, which nothing reads any more.
        defaults.removeObjectForKey(KEY_TOKEN)
    }

    override fun getNotesSource(): NotesSource? = NotesSource.decode(defaults.stringForKey(KEY_NOTES_SOURCE))

    override fun setNotesSource(source: NotesSource) {
        defaults.setObject(source.encode(), forKey = KEY_NOTES_SOURCE)
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

    override fun getNoteTemplate(): NoteTemplateSettings = NoteTemplateSettings.decode(defaults.stringForKey(KEY_NOTE_TEMPLATE))

    override fun setNoteTemplate(settings: NoteTemplateSettings) {
        defaults.setObject(settings.encode(), forKey = KEY_NOTE_TEMPLATE)
    }

    override fun getTagColors(): Map<String, Int> = decodeTagColors(defaults.stringForKey(KEY_TAG_COLORS))

    override fun setTagColors(colors: Map<String, Int>) {
        defaults.setObject(encodeTagColors(colors), forKey = KEY_TAG_COLORS)
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
        private const val KEY_NOTES_SOURCE = "notes_source"
        private const val KEY_REMINDERS = "reminders"
        private const val KEY_NEW_NOTES_FOLDER = "new_notes_folder"
        private const val KEY_PINNED_NOTES = "pinned_notes"
        private const val KEY_FILE_LINK_MAPPINGS = "file_link_mappings"
        private const val KEY_NOTE_TEMPLATE = "note_template"
        private const val KEY_TAG_COLORS = "tag_colors"
    }
}
