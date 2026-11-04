package com.korgnote.app.data

import com.korgnote.app.files.FileLinkMapping
import com.korgnote.app.files.encodeFileLinkMappings
import com.korgnote.app.notifications.ReminderSettings
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What settings are kept under, as they are read back after an upgrade. Each key and each stored
 * form below is what a device already holds: changing one here would lose that setting there.
 */
class DesktopSettingsKeysTest {

    private val node: Preferences = Preferences.userRoot().node("com.korgnote.app.test-${UUID.randomUUID()}")

    private val cipher = object : SecretCipher {
        override fun seal(plain: String): String = plain
        override fun open(sealed: String): String = sealed
    }

    private fun repository() = PersistentSettingsRepository(node, cipher)

    @AfterTest
    fun cleanUp() = node.removeNode()

    private val reminders = ReminderSettings(enabled = false, leadMinutes = 25, digestEnabled = true)
    private val template = NoteTemplateSettings(author = "Jean", dailyFolder = "days", extraHeader = "#+TODO: A | B")
    private val searches = listOf(SavedSearch(name = "Work", query = "tag:work"))
    private val mappings = listOf(FileLinkMapping("~/Sync", "/sdcard/Sync"))
    private val colors = mapOf("work" to 0x336699)
    private val places = listOf(ReadingPlace("a.org", offset = 120, pixels = 8))
    private val screen = LastScreen(tab = "CALENDAR", calendarMode = "WEEK", calendarDate = "2026-11-04", savedOn = "2026-11-04")

    @Test
    fun eachSettingIsWrittenUnderItsKey() {
        val repo = repository()
        repo.setServerHost("notes.local")
        repo.setServerPort(9000)
        repo.setNotesSource(NotesSource.WebDav)
        repo.setReminderSettings(reminders)
        repo.setNewNotesFolder("inbox")
        repo.setPinnedNotes(listOf("a.org", "b.org"))
        repo.setSavedSearches(searches)
        repo.setFileLinkMappings(mappings)
        repo.setNoteTemplate(template)
        repo.setTagColors(colors)
        repo.setReadingPlaces(places)
        repo.setLastScreen(screen)

        assertEquals("notes.local", node.get("server_host", null))
        assertEquals(9000, node.getInt("server_port", 0))
        assertEquals(NotesSource.WebDav.encode(), node.get("notes_source", null))
        assertEquals(reminders.encode(), node.get("reminders", null))
        assertEquals("inbox", node.get("new_notes_folder", null))
        assertEquals("a.org\nb.org", node.get("pinned_notes", null))
        assertEquals(encodeSavedSearches(searches), node.get("saved_searches", null))
        assertEquals(encodeFileLinkMappings(mappings), node.get("file_link_mappings", null))
        assertEquals(template.encode(), node.get("note_template", null))
        assertEquals(encodeTagColors(colors), node.get("tag_colors", null))
        assertEquals(encodeReadingPlaces(places), node.get("reading_places", null))
        assertEquals(encodeLastScreen(screen), node.get("last_screen", null))
    }

    @Test
    fun whatADeviceAlreadyHoldsIsReadBack() {
        node.put("server_host", "notes.local")
        node.putInt("server_port", 9000)
        node.put("notes_source", NotesSource.WebDav.encode())
        node.put("reminders", reminders.encode())
        node.put("new_notes_folder", "inbox")
        node.put("pinned_notes", "a.org\nb.org")
        node.put("saved_searches", encodeSavedSearches(searches))
        node.put("file_link_mappings", encodeFileLinkMappings(mappings))
        node.put("note_template", template.encode())
        node.put("tag_colors", encodeTagColors(colors))
        node.put("reading_places", encodeReadingPlaces(places))
        node.put("last_screen", encodeLastScreen(screen))

        val repo = repository()

        assertEquals("notes.local", repo.getServerHost())
        assertEquals(9000, repo.getServerPort())
        assertEquals(NotesSource.WebDav, repo.getNotesSource())
        assertEquals(reminders, repo.getReminderSettings())
        assertEquals("inbox", repo.getNewNotesFolder())
        assertEquals(listOf("a.org", "b.org"), repo.getPinnedNotes())
        assertEquals(searches, repo.getSavedSearches())
        assertEquals(mappings, repo.getFileLinkMappings())
        assertEquals(template, repo.getNoteTemplate())
        assertEquals(colors, repo.getTagColors())
        assertEquals(places, repo.getReadingPlaces())
        assertEquals(screen, repo.getLastScreen())
    }

    @Test
    fun nothingKeptReadsAsTheDefaults() {
        val repo = repository()

        assertEquals(null, repo.getNotesSource())
        assertEquals(ReminderSettings(), repo.getReminderSettings())
        assertEquals("", repo.getNewNotesFolder())
        assertEquals(emptyList(), repo.getPinnedNotes())
        assertEquals(emptyList(), repo.getSavedSearches())
        assertEquals(emptyList(), repo.getFileLinkMappings())
        assertEquals(NoteTemplateSettings(), repo.getNoteTemplate())
        assertEquals(emptyMap(), repo.getTagColors())
        assertEquals(emptyList(), repo.getReadingPlaces())
        assertEquals(LastScreen(), repo.getLastScreen())
    }

    /** A value Preferences would refuse: the oldest places go first, rather than the setting failing. */
    @Test
    fun readingPlacesTooLongToKeepLoseTheOldest() {
        val many = (0 until 200).map { ReadingPlace("folder/a-rather-long-note-name-$it.org", offset = it) }

        repository().setReadingPlaces(many)

        val kept = repository().getReadingPlaces()
        assert(kept.isNotEmpty() && kept.size < many.size) { "kept ${kept.size}" }
        assertEquals(many.take(kept.size), kept)
    }
}
