package com.korgnote.app.viewmodel

import com.korgnote.app.data.OrgRepository
import com.korgnote.common.fs.Folder
import com.korgnote.common.fs.TreeUtils
import com.korgnote.common.org.OrgSection
import com.korgnote.common.org.toOrgDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Opening a file at a particular heading, the way the calendar does.
 *
 * A file's text is fetched asynchronously, so the heading can only be located once it has arrived.
 * Resolving it any earlier searches whichever file was open before and scrolls to the wrong place —
 * which is what these tests pin down.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarNavigationTest {

    private val agenda = "agenda.org"
    private val other = "other.org"

    // Deliberately different shapes: a heading at index 1 here is at a different index there, so a
    // lookup against the wrong file cannot accidentally give the right answer.
    private val agendaText = """
        #+TITLE: Agenda

        * TODO Alpha
        * TODO Final Review                          :work:
        * TODO Review                                :work:home:
        * TODO Gamma
    """.trimIndent() + "\n"

    private val otherText = """
        #+TITLE: Other

        * TODO Gamma                                 :work:
        * TODO Delta
    """.trimIndent() + "\n"

    private class FakeRepository(private val files: MutableMap<String, String>) : OrgRepository {
        override suspend fun fetchAllContents(): Map<String, String> = files.toMap()

        override suspend fun fetchTree(): Folder =
            TreeUtils.buildFromPaths(files.keys.sorted(), rootName = "Org")

        override suspend fun fetchRawContent(path: String): String = files.getValue(path)

        override suspend fun saveContent(path: String, content: String) {
            files[path] = content
        }
    }

    private lateinit var viewModel: FileTreeViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = FileTreeViewModel(
            FakeRepository(mutableMapOf(agenda to agendaText, other to otherText)),
        )
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun heading(title: String) = OrgSection(title = title, content = "", todo = "TODO", level = 1)

    /**
     * The heading as the calendar actually supplies it: taken from agenda.org's parsed document, so
     * its title has both the TODO keyword and the trailing tags stripped off.
     */
    private fun agendaHeadingFromCalendar(title: String): OrgSection {
        val document = agendaText.toOrgDocument()
        return document.children.filterIsInstance<OrgSection>().first { it.title == title }
    }

    @Test
    fun opensTheRequestedHeadingInTheRequestedFile() = runTest {
        viewModel.loadFromServer()

        viewModel.onFileSelected(agenda, scrollToHeading = heading("Gamma"))

        // preamble, Alpha, Final Review, Review, Gamma
        assertEquals(4, viewModel.scrollToElementIndex)
    }

    /** Real headings carry tags, and the parsed section's title has them stripped. */
    @Test
    fun findsAHeadingThatCarriesTags() = runTest {
        viewModel.loadFromServer()

        viewModel.onFileSelected(agenda, scrollToHeading = agendaHeadingFromCalendar("Review"))

        assertEquals(3, viewModel.scrollToElementIndex)
    }

    /** "Review" must not match "Final Review", which appears first. */
    @Test
    fun doesNotMatchAHeadingThatMerelyEndsWithTheSameWords() = runTest {
        viewModel.loadFromServer()

        viewModel.onFileSelected(agenda, scrollToHeading = agendaHeadingFromCalendar("Review"))

        assertEquals(3, viewModel.scrollToElementIndex, "matched 'Final Review' instead of 'Review'")
    }

    /** The regression: the heading must be found in the file being opened, not the one being left. */
    @Test
    fun resolvesAgainstTheNewFileNotThePreviousOne() = runTest {
        viewModel.loadFromServer()
        viewModel.onFileSelected(agenda)

        viewModel.onFileSelected(other, scrollToHeading = heading("Gamma"))

        // "Gamma" is index 3 in agenda.org but index 1 in other.org.
        assertEquals(otherText, viewModel.selectedFileContent)
        assertEquals(1, viewModel.scrollToElementIndex)
    }

    /**
     * A section's range belongs to the text the calendar indexed. When that no longer describes the
     * file — here the same title lives at a different offset in a different file — the title search
     * has to take over rather than the stale offset winning.
     */
    @Test
    fun fallsBackToTheTitleWhenTheRangeNoLongerFits() = runTest {
        viewModel.loadFromServer()

        viewModel.onFileSelected(other, scrollToHeading = agendaHeadingFromCalendar("Gamma"))

        // preamble, Gamma, Delta
        assertEquals(1, viewModel.scrollToElementIndex)
    }

    @Test
    fun aHeadingThatIsNotInTheFileScrollsNowhere() = runTest {
        viewModel.loadFromServer()

        viewModel.onFileSelected(other, scrollToHeading = heading("Alpha"))

        assertNull(viewModel.scrollToElementIndex)
    }

    @Test
    fun openingWithoutAHeadingClearsAnEarlierTarget() = runTest {
        viewModel.loadFromServer()
        viewModel.onFileSelected(agenda, scrollToHeading = heading("Beta"))

        viewModel.onFileSelected(other)

        assertNull(viewModel.scrollToElementIndex)
    }

    @Test
    fun theTargetIsConsumedOnceItHasBeenScrolledTo() = runTest {
        viewModel.loadFromServer()
        viewModel.onFileSelected(agenda, scrollToHeading = heading("Beta"))

        viewModel.onScrolledToElement()

        assertNull(viewModel.scrollToElementIndex)
    }
}
