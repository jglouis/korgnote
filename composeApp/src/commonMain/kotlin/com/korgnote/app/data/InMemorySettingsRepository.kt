package com.korgnote.app.data

import com.korgnote.app.files.FileLinkMapping
import com.korgnote.app.notifications.ReminderSettings
import com.korgnote.common.SERVER_PORT
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow

class InMemorySettingsRepository(
    initialHost: String = defaultServerBaseUrl()
        .removePrefix("http://")
        .removePrefix("https://")
        .substringBefore(":"),
    initialPort: Int = SERVER_PORT,
    initialToken: String = "",
) : SettingsRepository {
    private val host = MutableStateFlow(initialHost)
    private val port = MutableStateFlow(initialPort)
    private val token = MutableStateFlow(initialToken)

    override val serverHostFlow: Flow<String> = host
    override val serverPortFlow: Flow<Int> = port
    override val serverTokenFlow: Flow<String> = token

    override fun getServerHost(): String = host.value
    override fun getServerPort(): Int = port.value
    override fun getServerToken(): String = token.value

    override fun setServerHost(host: String) {
        this.host.value = host
    }

    override fun setServerPort(port: Int) {
        this.port.value = port
    }

    override fun setServerToken(token: String) {
        this.token.value = token
    }

    private var reminders = ReminderSettings()
    override fun getReminderSettings(): ReminderSettings = reminders
    override fun setReminderSettings(settings: ReminderSettings) {
        reminders = settings
    }

    private var newNotesFolder = ""
    override fun getNewNotesFolder(): String = newNotesFolder
    override fun setNewNotesFolder(folder: String) {
        newNotesFolder = folder
    }

    private var pinnedNotes = emptyList<String>()
    override fun getPinnedNotes(): List<String> = pinnedNotes
    override fun setPinnedNotes(paths: List<String>) {
        pinnedNotes = paths
    }

    private var fileLinkMappings = emptyList<FileLinkMapping>()
    override fun getFileLinkMappings(): List<FileLinkMapping> = fileLinkMappings
    override fun setFileLinkMappings(mappings: List<FileLinkMapping>) {
        fileLinkMappings = mappings
    }

    private var noteTemplate = NoteTemplateSettings()
    override fun getNoteTemplate(): NoteTemplateSettings = noteTemplate
    override fun setNoteTemplate(settings: NoteTemplateSettings) {
        noteTemplate = settings
    }

    private var tagColors = emptyMap<String, Int>()
    override fun getTagColors(): Map<String, Int> = tagColors
    override fun setTagColors(colors: Map<String, Int>) {
        tagColors = colors
    }
}
