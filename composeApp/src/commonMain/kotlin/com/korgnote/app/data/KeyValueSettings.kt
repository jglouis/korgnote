package com.korgnote.app.data

import com.korgnote.app.files.FileLinkMapping
import com.korgnote.app.files.decodeFileLinkMappings
import com.korgnote.app.files.encodeFileLinkMappings
import com.korgnote.app.notifications.ReminderSettings

/**
 * The settings every platform keeps the same way: as a string under a key, in whatever it keeps
 * strings in. The server's address and the secrets, which differ, are the platforms' own.
 *
 * The keys are what devices already hold: renaming one loses that setting on all of them.
 */
abstract class KeyValueSettings : SettingsRepository {
    /** What is kept under [key], or null. */
    protected abstract fun read(key: String): String?

    /** Keeps [value] under [key]. */
    protected abstract fun write(key: String, value: String)

    override fun getNotesSource(): NotesSource? = NotesSource.decode(read(KEY_NOTES_SOURCE))

    override fun setNotesSource(source: NotesSource) = write(KEY_NOTES_SOURCE, source.encode())

    override fun getReminderSettings(): ReminderSettings = ReminderSettings.decode(read(KEY_REMINDERS))

    override fun setReminderSettings(settings: ReminderSettings) = write(KEY_REMINDERS, settings.encode())

    override fun getNewNotesFolder(): String = read(KEY_NEW_NOTES_FOLDER).orEmpty()

    override fun setNewNotesFolder(folder: String) = write(KEY_NEW_NOTES_FOLDER, folder)

    override fun getPinnedNotes(): List<String> = decodePinnedNotes(read(KEY_PINNED_NOTES))

    override fun setPinnedNotes(paths: List<String>) = write(KEY_PINNED_NOTES, encodePinnedNotes(paths))

    override fun getSavedSearches(): List<SavedSearch> = decodeSavedSearches(read(KEY_SAVED_SEARCHES))

    override fun setSavedSearches(searches: List<SavedSearch>) = write(KEY_SAVED_SEARCHES, encodeSavedSearches(searches))

    override fun getFileLinkMappings(): List<FileLinkMapping> = decodeFileLinkMappings(read(KEY_FILE_LINK_MAPPINGS))

    override fun setFileLinkMappings(mappings: List<FileLinkMapping>) = write(KEY_FILE_LINK_MAPPINGS, encodeFileLinkMappings(mappings))

    override fun getNoteTemplate(): NoteTemplateSettings = NoteTemplateSettings.decode(read(KEY_NOTE_TEMPLATE))

    override fun setNoteTemplate(settings: NoteTemplateSettings) = write(KEY_NOTE_TEMPLATE, settings.encode())

    override fun getTagColors(): Map<String, Int> = decodeTagColors(read(KEY_TAG_COLORS))

    override fun setTagColors(colors: Map<String, Int>) = write(KEY_TAG_COLORS, encodeTagColors(colors))

    override fun getReadingPlaces(): List<ReadingPlace> = decodeReadingPlaces(read(KEY_READING_PLACES))

    override fun setReadingPlaces(places: List<ReadingPlace>) = write(KEY_READING_PLACES, encodeReadingPlaces(places))

    override fun getLastScreen(): LastScreen = decodeLastScreen(read(KEY_LAST_SCREEN))

    override fun setLastScreen(screen: LastScreen) = write(KEY_LAST_SCREEN, encodeLastScreen(screen))

    protected companion object {
        const val KEY_NOTES_SOURCE = "notes_source"
        const val KEY_REMINDERS = "reminders"
        const val KEY_NEW_NOTES_FOLDER = "new_notes_folder"
        const val KEY_PINNED_NOTES = "pinned_notes"
        const val KEY_SAVED_SEARCHES = "saved_searches"
        const val KEY_FILE_LINK_MAPPINGS = "file_link_mappings"
        const val KEY_NOTE_TEMPLATE = "note_template"
        const val KEY_TAG_COLORS = "tag_colors"
        const val KEY_READING_PLACES = "reading_places"
        const val KEY_LAST_SCREEN = "last_screen"
    }
}
