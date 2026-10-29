package com.korgnote.app.data

import com.korgnote.app.files.FileLinkMapping
import com.korgnote.app.files.decodeFileLinkMappings
import com.korgnote.app.files.encodeFileLinkMappings
import com.korgnote.app.notifications.ReminderSettings
import com.korgnote.common.SERVER_PORT
import kotlinx.browser.localStorage
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Settings kept in the page's `localStorage`, so the token and pinned notes survive a reload.
 *
 * Host and port are not stored: the web app is served by the server it talks to, so they always
 * come from the page origin, and a stored pair would outlive a move to another address. Storage
 * can be unavailable (blocked site data, some private windows); the app then works as before,
 * forgetting on reload.
 */
class PersistentSettingsRepository : SettingsRepository {
    private val hostFlow = MutableStateFlow(
        defaultServerBaseUrl().removePrefix("http://").removePrefix("https://").substringBefore(":")
    )
    private val portFlow = MutableStateFlow(
        defaultServerBaseUrl().substringAfterLast(':').toIntOrNull() ?: SERVER_PORT
    )
    private var pairedServer: PairedServer? = PairedServer.decode(load(KEY_PAIRED_SERVER))


    override fun getServerHost(): String = hostFlow.value
    override fun getServerPort(): Int = portFlow.value
    override fun getPairedServer(): PairedServer? = pairedServer

    override fun setServerHost(host: String) {
        hostFlow.value = host
    }

    override fun setServerPort(port: Int) {
        portFlow.value = port
    }

    override fun setPairedServer(server: PairedServer?) {
        pairedServer = server
        store(KEY_PAIRED_SERVER, server?.encode().orEmpty())
        // The shared token from before pairing, which nothing reads any more.
        store(KEY_TOKEN, "")
    }

    // A browser has no folder of notes to offer: the server this page comes from is the only source.
    override fun getNotesSource(): NotesSource = NotesSource.Server

    override fun setNotesSource(source: NotesSource) {}

    override fun getReminderSettings(): ReminderSettings = ReminderSettings.decode(load(KEY_REMINDERS))

    override fun setReminderSettings(settings: ReminderSettings) {
        store(KEY_REMINDERS, settings.encode())
    }

    override fun getNewNotesFolder(): String = load(KEY_NEW_NOTES_FOLDER).orEmpty()

    override fun setNewNotesFolder(folder: String) {
        store(KEY_NEW_NOTES_FOLDER, folder)
    }

    override fun getPinnedNotes(): List<String> = decodePinnedNotes(load(KEY_PINNED_NOTES))

    override fun setPinnedNotes(paths: List<String>) {
        store(KEY_PINNED_NOTES, encodePinnedNotes(paths))
    }

    override fun getSavedSearches(): List<SavedSearch> = decodeSavedSearches(load(KEY_SAVED_SEARCHES))

    override fun setSavedSearches(searches: List<SavedSearch>) {
        store(KEY_SAVED_SEARCHES, encodeSavedSearches(searches))
    }

    override fun getFileLinkMappings(): List<FileLinkMapping> = decodeFileLinkMappings(load(KEY_FILE_LINK_MAPPINGS))

    override fun setFileLinkMappings(mappings: List<FileLinkMapping>) {
        store(KEY_FILE_LINK_MAPPINGS, encodeFileLinkMappings(mappings))
    }

    override fun getNoteTemplate(): NoteTemplateSettings = NoteTemplateSettings.decode(load(KEY_NOTE_TEMPLATE))

    override fun setNoteTemplate(settings: NoteTemplateSettings) {
        store(KEY_NOTE_TEMPLATE, settings.encode())
    }

    override fun getTagColors(): Map<String, Int> = decodeTagColors(load(KEY_TAG_COLORS))

    override fun setTagColors(colors: Map<String, Int>) {
        store(KEY_TAG_COLORS, encodeTagColors(colors))
    }

    private fun load(key: String): String? = runCatching { localStorage.getItem(PREFIX + key) }.getOrNull()

    private fun store(key: String, value: String) {
        runCatching { localStorage.setItem(PREFIX + key, value) }
    }

    companion object {
        private const val PREFIX = "korgnote."
        private const val KEY_TOKEN = "server_token"
        private const val KEY_PAIRED_SERVER = "paired_server"
        private const val KEY_REMINDERS = "reminders"
        private const val KEY_NEW_NOTES_FOLDER = "new_notes_folder"
        private const val KEY_PINNED_NOTES = "pinned_notes"
        private const val KEY_SAVED_SEARCHES = "saved_searches"
        private const val KEY_FILE_LINK_MAPPINGS = "file_link_mappings"
        private const val KEY_NOTE_TEMPLATE = "note_template"
        private const val KEY_TAG_COLORS = "tag_colors"
    }
}
