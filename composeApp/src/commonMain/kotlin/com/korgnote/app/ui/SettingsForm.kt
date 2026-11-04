package com.korgnote.app.ui

import com.korgnote.app.data.NoteTemplateSettings
import com.korgnote.app.data.SettingsRepository
import com.korgnote.app.data.usesLocalServer
import com.korgnote.app.files.FileLinkMapping
import com.korgnote.app.notifications.ReminderSettings
import com.korgnote.common.fs.NotePaths
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.settings_error_daily_folder
import korgnote.composeapp.generated.resources.settings_error_folder
import korgnote.composeapp.generated.resources.settings_error_host_port
import korgnote.composeapp.generated.resources.settings_error_minutes
import kotlinx.datetime.LocalTime
import org.jetbrains.compose.resources.StringResource

/** What Settings holds, as it was typed: tidied only when it is saved. */
internal data class SettingsForm(
    val host: String = "",
    val portText: String = "",
    val remindersOn: Boolean = true,
    val leadText: String = "",
    val digestOn: Boolean = false,
    val digestTime: LocalTime = ReminderSettings().digestTime,
    val newNotesFolder: String = "",
    val fileLinks: List<FileLinkMapping> = emptyList(),
    val author: String = "",
    val dailyFolder: String = "",
    val extraHeader: String = "",
    val tagColors: Map<String, Int> = emptyMap(),
) {
    private val port: Int? get() = portText.toIntOrNull()
    private val lead: Int? get() = leadText.toIntOrNull()
    private val folder: String get() = newNotesFolder.trim().trim('/')
    private val daily: String get() = dailyFolder.trim().trim('/')

    /** What is the first thing wrong with it, in the order of the form; null when it can be saved. */
    fun validate(): StringResource? {
        val port = port
        val lead = lead
        return when {
            usesLocalServer && (host.isBlank() || port == null || port !in 1..65535) -> Res.string.settings_error_host_port
            remindersOn && (lead == null || lead !in 0..1440) -> Res.string.settings_error_minutes
            // Checked as a note path would be, so a bad folder is refused here and not by the server.
            folder.isNotEmpty() && NotePaths.normalize("$folder/x.org") == null -> Res.string.settings_error_folder
            daily.isNotEmpty() && NotePaths.normalize("$daily/x.org") == null -> Res.string.settings_error_daily_folder
            else -> fileLinkMappingsError(fileLinks)
        }
    }

    /** Writes it to [repo], tidied. For a form [validate] has nothing to say about. */
    fun save(repo: SettingsRepository) {
        val port = port
        if (usesLocalServer && port != null) {
            repo.setServerHost(host.trim())
            repo.setServerPort(port)
        }
        val current = repo.getReminderSettings()
        repo.setReminderSettings(
            ReminderSettings(
                enabled = remindersOn,
                leadMinutes = lead ?: current.leadMinutes,
                digestEnabled = digestOn,
                digestTime = digestTime,
            ),
        )
        repo.setNewNotesFolder(folder)
        repo.setNoteTemplate(NoteTemplateSettings(author.trim(), daily, extraHeader.trimEnd()))
        repo.setTagColors(tagColors)
        TagColors.chosen = tagColors
        repo.setFileLinkMappings(
            fileLinks.filter { it.from.isNotBlank() }.map { FileLinkMapping(it.from.trim(), it.to.trim()) },
        )
    }

    companion object {
        fun load(repo: SettingsRepository): SettingsForm {
            val reminders = repo.getReminderSettings()
            val template = repo.getNoteTemplate()
            return SettingsForm(
                host = repo.getServerHost(),
                portText = repo.getServerPort().toString(),
                remindersOn = reminders.enabled,
                leadText = reminders.leadMinutes.toString(),
                digestOn = reminders.digestEnabled,
                digestTime = reminders.digestTime,
                newNotesFolder = repo.getNewNotesFolder(),
                fileLinks = repo.getFileLinkMappings(),
                author = template.author,
                dailyFolder = template.dailyFolder,
                extraHeader = template.extraHeader,
                tagColors = repo.getTagColors(),
            )
        }
    }
}
