package com.korgnote.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import com.korgnote.app.data.InMemorySettingsRepository
import com.korgnote.app.data.NoteTemplateSettings
import com.korgnote.app.data.NotesSource
import com.korgnote.app.data.WebDavAccount
import com.korgnote.app.files.FileLinkMapping
import com.korgnote.app.notifications.ReminderSettings
import kotlinx.datetime.LocalTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The form behind Settings: what it opens on, when it offers to save, what it refuses and what it writes. */
@OptIn(ExperimentalTestApi::class)
class SettingsScreenTest {
    private val repo = InMemorySettingsRepository(initialHost = "box.local", initialPort = 8123).apply {
        setReminderSettings(ReminderSettings(enabled = true, leadMinutes = 15, digestEnabled = false, digestTime = LocalTime(7, 45)))
        setNewNotesFolder("inbox")
        setNoteTemplate(NoteTemplateSettings(author = "Ada", dailyFolder = "days", extraHeader = "#+TODO: A | B"))
    }
    private var saves = 0

    @AfterTest
    fun forgetTheColours() {
        TagColors.chosen = emptyMap()
    }

    private fun ComposeUiTest.show(source: NotesSource = NotesSource.Server, onChangeSource: (() -> Unit)? = null) = setContent {
        MaterialTheme {
            SettingsScreen(
                repo = repo,
                onSaved = { saves++ },
                notesSource = source,
                onChangeNotesSource = onChangeSource,
            )
        }
    }

    private fun ComposeUiTest.field(label: String): SemanticsNodeInteraction = onNode(hasSetTextAction() and hasText(label, substring = true))

    private val hostLabel = "Host (usually localhost)"
    private val minutesLabel = "Minutes before"
    private val notesFolderLabel = "Folder for new notes (empty for the top)"
    private val dailiesLabel = "Folder for dailies (empty for the top)"
    private val headerLabel = "Extra header lines, such as #+TODO:"

    private fun ComposeUiTest.save() {
        onNodeWithText("Save", useUnmergedTree = true).performClick()
        waitForIdle()
    }

    @Test
    fun itOpensOnWhatIsSavedAndOffersNoSave() = runComposeUiTest {
        show()
        waitForIdle()

        field(hostLabel).assertTextContains("box.local")
        field("Port").assertTextContains("8123")
        field(minutesLabel).assertTextContains("15")
        field(notesFolderLabel).assertTextContains("inbox")
        field(dailiesLabel).assertTextContains("days")
        field("Author").assertTextContains("Ada")
        field(headerLabel).assertTextContains("#+TODO: A | B")
        onNodeWithText("Save", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun aChangeOffersSaveAndUndoingItTakesTheOfferAway() = runComposeUiTest {
        show()
        waitForIdle()

        field("Author").performTextReplacement("Grace")
        onNodeWithText("Save", useUnmergedTree = true).assertExists()

        field("Author").performTextReplacement("Ada")
        waitForIdle()
        onNodeWithText("Save", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun savingWritesEverythingTidiedAndSaysSo() = runComposeUiTest {
        show()
        waitForIdle()

        field(hostLabel).performTextReplacement("  other.local ")
        field("Port").performTextReplacement("9000")
        field(minutesLabel).performTextReplacement("30")
        field(notesFolderLabel).performTextReplacement(" /notes/inbox/ ")
        field(dailiesLabel).performTextReplacement(" /journal/ ")
        field("Author").performTextReplacement("  Grace ")
        field(headerLabel).performTextReplacement("#+TODO: X | Y\n\n")
        save()

        assertEquals("other.local", repo.getServerHost())
        assertEquals(9000, repo.getServerPort())
        assertEquals(ReminderSettings(true, 30, false, LocalTime(7, 45)), repo.getReminderSettings())
        assertEquals("notes/inbox", repo.getNewNotesFolder())
        assertEquals(NoteTemplateSettings("Grace", "journal", "#+TODO: X | Y"), repo.getNoteTemplate())
        assertEquals(1, saves)
        // Nothing to save any more, though what was typed stays as it was typed.
        field(hostLabel).assertTextContains("  other.local ")
        onNodeWithText("Save", useUnmergedTree = true).assertDoesNotExist()
    }

    private fun ComposeUiTest.assertRefused(message: String) {
        onNodeWithText(message).assertExists()
        assertEquals(0, saves)
        assertEquals("box.local", repo.getServerHost())
        assertEquals("inbox", repo.getNewNotesFolder())
    }

    @Test
    fun aBlankHostIsRefused() = runComposeUiTest {
        show()
        waitForIdle()
        field(hostLabel).performTextReplacement("  ")
        field(notesFolderLabel).performTextReplacement("elsewhere")
        save()
        assertRefused("Please enter a valid host and port (1-65535).")
    }

    @Test
    fun aPortOutOfRangeIsRefused() = runComposeUiTest {
        show()
        waitForIdle()
        field("Port").performTextReplacement("70000")
        save()
        assertRefused("Please enter a valid host and port (1-65535).")
    }

    @Test
    fun minutesOutOfRangeAreRefused() = runComposeUiTest {
        show()
        waitForIdle()
        field(minutesLabel).performTextReplacement("1441")
        save()
        assertRefused("Please enter how many minutes before (0-1440).")
    }

    @Test
    fun aFolderOutsideTheNotesIsRefused() = runComposeUiTest {
        show()
        waitForIdle()
        field(notesFolderLabel).performTextReplacement("../outside")
        save()
        assertRefused("Please enter a folder inside the notes, like inbox or notes/inbox.")
    }

    @Test
    fun aDailiesFolderOutsideTheNotesIsRefused() = runComposeUiTest {
        show()
        waitForIdle()
        field(dailiesLabel).performTextReplacement("../outside")
        save()
        assertRefused("Please enter a folder inside the notes for dailies, like daily.")
    }

    @Test
    fun aFileLinkFolderWrittenWronglyIsRefused() = runComposeUiTest {
        repo.setFileLinkMappings(listOf(FileLinkMapping("Sync", "/data/sync")))
        show()
        waitForIdle()
        field("Author").performTextReplacement("Grace")
        save()
        assertRefused("Please write each folder in links as the links do, starting with ~ or /.")
    }

    /** One thing at a time, in the order of the form. */
    @Test
    fun theFirstThingWrongIsTheOneSaid() = runComposeUiTest {
        show()
        waitForIdle()
        field("Port").performTextReplacement("0")
        field(minutesLabel).performTextReplacement("1441")
        save()
        onNodeWithText("Please enter a valid host and port (1-65535).").assertExists()
        onNodeWithText("Please enter how many minutes before (0-1440).").assertDoesNotExist()
    }

    @Test
    fun whatWasRefusedStopsBeingSaidOnceRight() = runComposeUiTest {
        show()
        waitForIdle()
        field("Port").performTextReplacement("70000")
        save()
        field("Port").performTextReplacement("7000")
        save()
        onNodeWithText("Please enter a valid host and port (1-65535).").assertDoesNotExist()
        assertEquals(7000, repo.getServerPort())
    }

    @Test
    fun minutesAreNotAskedOfRemindersTurnedOff() = runComposeUiTest {
        show()
        waitForIdle()
        field(minutesLabel).performTextReplacement("")
        onNodeWithText("Remind me before timed entries").performScrollTo().performClick()
        waitForIdle()
        onAllNodesWithText(minutesLabel).assertCountEquals(0)
        save()

        // The minutes it had, kept for when they are turned back on.
        assertEquals(ReminderSettings(false, 15, false, LocalTime(7, 45)), repo.getReminderSettings())
    }

    @Test
    fun thePortAndTheMinutesTakeOnlyDigits() = runComposeUiTest {
        show()
        waitForIdle()
        field("Port").performTextReplacement("80a81")
        field("Port").assertTextContains("8081")
        field(minutesLabel).performTextReplacement("1x23456")
        field(minutesLabel).assertTextContains("1234")
    }

    @Test
    fun theDigestsTimeShowsWithItAndIsPickedInADialog() = runComposeUiTest {
        show()
        waitForIdle()
        onNodeWithText("At 07:45").assertDoesNotExist()

        onNodeWithText("Morning digest of the day's agenda").performScrollTo().performClick()
        onNodeWithText("At 07:45").performScrollTo().performClick()
        onNodeWithText("Morning digest at").assertExists()
        onNodeWithText("Cancel").performClick()
        onNodeWithText("Morning digest at").assertDoesNotExist()
        onNodeWithText("At 07:45").performScrollTo().performClick()
        onNodeWithText("OK").performClick()
        onNodeWithText("Morning digest at").assertDoesNotExist()
        save()

        assertEquals(ReminderSettings(true, 15, true, LocalTime(7, 45)), repo.getReminderSettings())
    }

    @Test
    fun tagColoursTakeEffectOnceSaved() = runComposeUiTest {
        repo.setTagColors(mapOf("work" to 0xC62828))
        show()
        waitForIdle()
        assertEquals(emptyMap(), TagColors.chosen)

        field("Author").performTextReplacement("Grace")
        save()

        assertEquals(mapOf("work" to 0xC62828), TagColors.chosen)
    }

    @Test
    fun blankFileLinksAreLeftOutAndTheRestTrimmed() = runComposeUiTest {
        repo.setFileLinkMappings(listOf(FileLinkMapping(" ~/Sync ", " /data/sync "), FileLinkMapping("", "")))
        show()
        waitForIdle()
        field("Author").performTextReplacement("Grace")
        save()

        assertEquals(listOf(FileLinkMapping("~/Sync", "/data/sync")), repo.getFileLinkMappings())
    }

    @Test
    fun onTheServerItsSectionShowsAndWhereTheNotesAreOnlyWhenOffered() = runComposeUiTest {
        show()
        waitForIdle()
        onNodeWithText("Host (usually localhost)", substring = true).assertExists()
        onNodeWithText("Change where notes are").assertDoesNotExist()
    }

    @Test
    fun whereTheNotesAreCanBeChangedWhenOffered() = runComposeUiTest {
        var changing = 0
        show(onChangeSource = { changing++ })
        onNodeWithText("Synced with the KorgNote server on your computer.").assertExists()
        onNodeWithText("Change where notes are").performScrollTo().performClick()
        assertEquals(1, changing)
    }

    @Test
    fun inAFolderTheServersSectionGoes() = runComposeUiTest {
        show(source = NotesSource.Folder("/some/where"), onChangeSource = {})
        onNodeWithText("In the folder", substring = true).assertExists()
        onNodeWithText("Host (usually localhost)", substring = true).assertDoesNotExist()
    }

    @Test
    fun onWebDavItSaysWhichFolderAndAs() = runComposeUiTest {
        repo.setWebDavAccount(WebDavAccount("https://cloud.example/notes", "ada", "secret"))
        show(source = NotesSource.WebDav, onChangeSource = {})
        onNodeWithText("Synced with the WebDAV folder https://cloud.example/notes, as ada. Experimental.").assertExists()
        onNodeWithText("Host (usually localhost)", substring = true).assertDoesNotExist()
    }

    @Test
    fun onWebDavWithNoAccountItSaysSoPlainly() = runComposeUiTest {
        show(source = NotesSource.WebDav, onChangeSource = {})
        onNodeWithText("Synced with a WebDAV server. Experimental.").assertExists()
    }
}
