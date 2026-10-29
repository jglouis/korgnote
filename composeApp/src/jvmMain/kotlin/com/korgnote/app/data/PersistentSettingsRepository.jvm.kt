package com.korgnote.app.data

import com.korgnote.app.files.FileLinkMapping
import com.korgnote.app.files.decodeFileLinkMappings
import com.korgnote.app.files.encodeFileLinkMappings
import com.korgnote.app.notifications.ReminderSettings
import com.korgnote.common.SERVER_PORT
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.prefs.Preferences

class PersistentSettingsRepository : SettingsRepository {
    private val prefs: Preferences = Preferences.userRoot().node("com.korgnote.app")

    private val hostFlow = MutableStateFlow(loadHost())
    private val portFlow = MutableStateFlow(loadPort())
    private var pairedServer: PairedServer? = PairedServer.decode(prefs.get(KEY_PAIRED_SERVER, null))


    override fun getServerHost(): String = hostFlow.value
    override fun getServerPort(): Int = portFlow.value
    override fun getPairedServer(): PairedServer? = pairedServer

    override fun setServerHost(host: String) {
        hostFlow.value = host
        prefs.put(KEY_HOST, host)
    }

    override fun setServerPort(port: Int) {
        portFlow.value = port
        prefs.putInt(KEY_PORT, port)
    }

    override fun setPairedServer(server: PairedServer?) {
        pairedServer = server
        if (server == null) prefs.remove(KEY_PAIRED_SERVER) else prefs.put(KEY_PAIRED_SERVER, server.encode())
        // The shared token from before pairing, which nothing reads any more.
        prefs.remove(KEY_TOKEN)
    }

    override fun getNotesSource(): NotesSource? = NotesSource.decode(prefs.get(KEY_NOTES_SOURCE, null))

    override fun setNotesSource(source: NotesSource) {
        prefs.put(KEY_NOTES_SOURCE, source.encode())
    }

    override fun getReminderSettings(): ReminderSettings = ReminderSettings.decode(prefs.get(KEY_REMINDERS, null))

    override fun setReminderSettings(settings: ReminderSettings) {
        prefs.put(KEY_REMINDERS, settings.encode())
    }

    override fun getNewNotesFolder(): String = prefs.get(KEY_NEW_NOTES_FOLDER, "")

    override fun setNewNotesFolder(folder: String) {
        prefs.put(KEY_NEW_NOTES_FOLDER, folder)
    }

    override fun getPinnedNotes(): List<String> = decodePinnedNotes(prefs.get(KEY_PINNED_NOTES, null))

    override fun setPinnedNotes(paths: List<String>) {
        prefs.put(KEY_PINNED_NOTES, encodePinnedNotes(paths))
    }

    override fun getSavedSearches(): List<SavedSearch> = decodeSavedSearches(prefs.get(KEY_SAVED_SEARCHES, null))

    override fun setSavedSearches(searches: List<SavedSearch>) {
        prefs.put(KEY_SAVED_SEARCHES, encodeSavedSearches(searches))
    }

    override fun getFileLinkMappings(): List<FileLinkMapping> =
        decodeFileLinkMappings(prefs.get(KEY_FILE_LINK_MAPPINGS, null))

    override fun setFileLinkMappings(mappings: List<FileLinkMapping>) {
        prefs.put(KEY_FILE_LINK_MAPPINGS, encodeFileLinkMappings(mappings))
    }

    override fun getNoteTemplate(): NoteTemplateSettings = NoteTemplateSettings.decode(prefs.get(KEY_NOTE_TEMPLATE, null))

    override fun setNoteTemplate(settings: NoteTemplateSettings) {
        prefs.put(KEY_NOTE_TEMPLATE, settings.encode())
    }

    override fun getTagColors(): Map<String, Int> = decodeTagColors(prefs.get(KEY_TAG_COLORS, null))

    override fun setTagColors(colors: Map<String, Int>) {
        prefs.put(KEY_TAG_COLORS, encodeTagColors(colors))
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
        private const val KEY_PAIRED_SERVER = "paired_server"
        private const val KEY_NOTES_SOURCE = "notes_source"
        private const val KEY_REMINDERS = "reminders"
        private const val KEY_NEW_NOTES_FOLDER = "new_notes_folder"
        private const val KEY_PINNED_NOTES = "pinned_notes"
        private const val KEY_SAVED_SEARCHES = "saved_searches"
        private const val KEY_FILE_LINK_MAPPINGS = "file_link_mappings"
        private const val KEY_NOTE_TEMPLATE = "note_template"
        private const val KEY_TAG_COLORS = "tag_colors"
    }
}
