package com.korgnote.app.data

import com.korgnote.app.files.FileLinkMapping
import com.korgnote.app.notifications.ReminderSettings

interface SettingsRepository {
    fun getServerHost(): String
    fun getServerPort(): Int

    fun setServerHost(host: String)
    fun setServerPort(port: Int)

    /**
     * The computer this device is paired with, or null. While paired, the app reaches the server
     * there, over TLS; [getServerHost] and [getServerPort] name the server on this computer
     * itself, which is what the desktop app and the web client use.
     */
    fun getPairedServer(): PairedServer?

    fun setPairedServer(server: PairedServer?)

    /**
     * Where the notes are: the server's replica, or a folder of the user's. Null until chosen; see
     * [chosenNotesSource], which is what to read.
     */
    fun getNotesSource(): NotesSource?

    fun setNotesSource(source: NotesSource)

    /**
     * The WebDAV folder the notes sync with when they are kept there (experimental); null until one
     * is set up. Where WebDAV is not offered there is never one, which is what the defaults say.
     */
    fun getWebDavAccount(): WebDavAccount? = null

    fun setWebDavAccount(account: WebDavAccount?) {}

    /** Reminder lead time and daily digest (T18). */
    fun getReminderSettings(): ReminderSettings

    fun setReminderSettings(settings: ReminderSettings)

    /**
     * Where a new note goes (T20), relative to the notes directory; empty for its top. A capture
     * inbox: the folder a note typed in a hurry lands in, to be filed properly later.
     */
    fun getNewNotesFolder(): String

    fun setNewNotesFolder(folder: String)

    /**
     * Notes pinned for quick access, as paths in the order they are shown. Kept per device: what a
     * phone needs at hand is not what a desktop does.
     */
    fun getPinnedNotes(): List<String>

    fun setPinnedNotes(paths: List<String>)

    /** Searches kept by name to run again, in the order they are listed. Kept per device, like the pins. */
    fun getSavedSearches(): List<SavedSearch>

    fun setSavedSearches(searches: List<SavedSearch>)

    /**
     * Where the folders file links name are on this device, as [FileLinkMapping]s. Kept per device,
     * like the pins: `~/Sync` is one place on the laptop the notes were written on, another on a phone.
     */
    fun getFileLinkMappings(): List<FileLinkMapping>

    fun setFileLinkMappings(mappings: List<FileLinkMapping>)

    /** The author, daily folder and extra header new notes and dailies are written with. */
    fun getNoteTemplate(): NoteTemplateSettings

    fun setNoteTemplate(settings: NoteTemplateSettings)

    /**
     * Colours chosen for tags, as `RRGGBB` by tag. Kept per device, like the pins; a tag with none
     * gets one from its name, which is the same everywhere without being kept at all.
     */
    fun getTagColors(): Map<String, Int>

    fun setTagColors(colors: Map<String, Int>)
}

/** Pinned notes as one stored string, a path per line: a note path never holds a line break. */
internal fun encodePinnedNotes(paths: List<String>): String = paths.joinToString("\n")

internal fun decodePinnedNotes(stored: String?): List<String> =
    stored.orEmpty().split('\n').filter { it.isNotBlank() }

