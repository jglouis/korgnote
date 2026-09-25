package com.korgnote.app.viewmodel

import com.korgnote.common.org.agenda.AgendaEntry
import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.InMemoryReplicaStorage
import com.korgnote.common.replica.SyncEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlin.test.*

/**
 * The calendar's view of the notes, and opening a file at the heading of one of its entries.
 *
 * A file's text is fetched asynchronously, so the heading can only be located once it has arrived.
 * Resolving it any earlier searches whichever file was open before and scrolls to the wrong place —
 * which is what the opening tests pin down.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarNavigationTest {

    private val agenda = "agenda.org"
    private val other = "other.org"
    private val today = LocalDate(2026, 9, 23)
    private val sep25 = LocalDate(2026, 9, 25)

    // Deliberately different shapes: a heading at index 1 here is at a different index there, so a
    // lookup against the wrong file cannot accidentally give the right answer.
    private val agendaText = """
        #+TITLE: Agenda

        * TODO Alpha
        * TODO Final Review                          :work:
        SCHEDULED: <2026-09-25 Fri>
        * TODO Review                                :work:home:
        SCHEDULED: <2026-09-25 Fri>
        * TODO Gamma
        DEADLINE: <2026-09-25 Fri>
    """.trimIndent() + "\n"

    private val otherText = """
        #+TITLE: Other

        * TODO Gamma                                 :work:
        SCHEDULED: <2026-09-25 Fri>
        * TODO Delta
    """.trimIndent() + "\n"

    private lateinit var viewModel: NotesViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val store = DocumentStore(InMemoryReplicaStorage())
        val api = FakeSyncApi(mutableMapOf(agenda to agendaText, other to otherText))
        viewModel = NotesViewModel(SyncEngine(store, api), store, today = { today })
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun entry(file: String, title: String): AgendaEntry =
        viewModel.agenda.index.on(sep25).map { it.entry }.first { it.file == file && it.title == title }

    @Test
    fun theCalendarSeesEveryDatedHeadingInEveryFile() = runTest {
        viewModel.server.load()

        val onThe25th = viewModel.agenda.index.on(sep25).map { it.entry.file to it.entry.title }

        assertEquals(
            listOf(agenda to "Final Review", agenda to "Review", agenda to "Gamma", other to "Gamma"),
            onThe25th,
        )
    }

    @Test
    fun theIndexFollowsAnEdit() = runTest {
        viewModel.server.load()
        viewModel.onFileSelected(other)

        viewModel.note.content = otherText.replace("* TODO Delta", "* TODO Delta\nSCHEDULED: <2026-09-25 Fri>")
        viewModel.saveCurrentFile()

        assertTrue(viewModel.agenda.index.on(sep25).any { it.entry.title == "Delta" })
    }

    @Test
    fun opensTheEntryAtItsHeading() = runTest {
        viewModel.server.load()

        viewModel.agenda.openEntry(entry(agenda, "Gamma"))

        // preamble, Alpha, Final Review, Review, Gamma
        assertEquals(4, viewModel.note.scrollToElementIndex)
    }

    /** "Review" must not land on "Final Review", which comes first and ends with the same word. */
    @Test
    fun opensTheHeadingItCameFromNotOneWithASimilarTitle() = runTest {
        viewModel.server.load()

        viewModel.agenda.openEntry(entry(agenda, "Review"))

        assertEquals(3, viewModel.note.scrollToElementIndex)
    }

    /** The regression: the heading must be found in the file being opened, not the one being left. */
    @Test
    fun resolvesAgainstTheNewFileNotThePreviousOne() = runTest {
        viewModel.server.load()
        viewModel.onFileSelected(agenda)

        viewModel.agenda.openEntry(entry(other, "Gamma"))

        // "Gamma" is index 4 in agenda.org but index 1 in other.org.
        assertEquals(otherText, viewModel.note.content)
        assertEquals(1, viewModel.note.scrollToElementIndex)
    }

    @Test
    fun openingWithoutATargetClearsAnEarlierOne() = runTest {
        viewModel.server.load()
        viewModel.agenda.openEntry(entry(agenda, "Gamma"))

        viewModel.onFileSelected(other)

        assertNull(viewModel.note.scrollToElementIndex)
    }

    @Test
    fun theTargetIsConsumedOnceItHasBeenScrolledTo() = runTest {
        viewModel.server.load()
        viewModel.agenda.openEntry(entry(agenda, "Gamma"))

        viewModel.note.onScrolledToElement()

        assertNull(viewModel.note.scrollToElementIndex)
    }
}
