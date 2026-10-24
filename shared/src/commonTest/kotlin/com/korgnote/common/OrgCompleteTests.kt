package com.korgnote.common

import com.korgnote.common.org.OrgTodoConfig
import com.korgnote.common.org.complete.CompletionContext
import com.korgnote.common.org.complete.CompletionItem
import com.korgnote.common.org.complete.applyTo
import com.korgnote.common.org.complete.complete
import com.korgnote.common.org.complete.restamp
import com.korgnote.common.org.complete.shiftedEnd
import com.korgnote.common.org.toOrgDocument
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Completion (T13): one test per row of the trigger table in `docs/06-editor.md`, plus the rules
 * about what must *not* fire.
 *
 * Two conventions run through all of it. The cursor is written as `‸` in the fixture and taken out
 * before parsing, because a completion is a function of where the caret is and spelling that out in
 * offsets makes the tests unreadable — and that character rather than the obvious `|`, because `|`
 * is Org syntax: it splits a `#+TODO:` line and starts every table row, so it would be found before
 * the intended caret. And [apply] performs the item the way the editor does — a
 * range replacement plus a caret — so each test asserts the text the user would end up with rather
 * than the shape of the item, which is what actually has to be right.
 */
class OrgCompleteTests {

    /** A Wednesday, so "tomorrow" and the weekday names are distinguishable. */
    private val today = LocalDate(2026, 9, 23)

    private val caretMarker = '‸'

    private val ctx = CompletionContext(
        knownTags = listOf("work", "home", "wishlist"),
        filePaths = listOf("notes/groceries.org", "projects/korgnote.org"),
        headings = listOf("Shopping", "Reading"),
        today = today,
    )

    /** Runs completion with the caret written as `‸`. */
    private fun completeAt(fixture: String, context: CompletionContext = ctx): List<CompletionItem> {
        val cursor = fixture.indexOf(caretMarker)
        require(cursor >= 0) { "the fixture must say where the caret is" }
        val text = fixture.removeRange(cursor, cursor + 1)
        return complete(text.toOrgDocument(), cursor, context)
    }

    /**
     * The text and caret the editor would end up with, caret written back as `‸`.
     *
     * Goes through [applyTo] rather than splicing by hand, so these tests cover the code the editor
     * actually runs — including the caret, which is half of what an item is for.
     */
    private fun apply(fixture: String, item: CompletionItem): String {
        val cursor = fixture.indexOf(caretMarker)
        val result = item.applyTo(fixture.removeRange(cursor, cursor + 1))
        return result.text.replaceRange(result.caret, result.caret, caretMarker.toString())
    }

    // --- Row 1: TODO keywords after the stars of a heading ---------------------------------------

    /** The acceptance criterion: `* ` then `TO` offers the document's own `#+TODO:` keywords. */
    @Test
    fun offersTheDocumentsOwnTodoKeywords() {
        val doc = "#+TODO: TODO NEXT WAITING | DONE CANCELLED\n\n* TO"
        val items = completeAt(
            "$doc‸",
            ctx.copy(todoKeywords = OrgTodoConfig(setOf("TODO", "NEXT", "WAITING"), setOf("DONE", "CANCELLED"))),
        )
        assertContentEquals(listOf("TODO"), items.map { it.label })
    }

    @Test
    fun offersEveryKeywordWhenNothingIsTypedYet() {
        val items = completeAt(
            "* ‸",
            ctx.copy(todoKeywords = OrgTodoConfig(setOf("TODO", "NEXT"), setOf("DONE"))),
        )
        assertContentEquals(listOf("TODO", "NEXT", "DONE"), items.map { it.label })
    }

    @Test
    fun fallsBackToOrgsOwnKeywordsWhenTheDocumentNamesNone() {
        val items = completeAt("* ‸", ctx.copy(todoKeywords = OrgTodoConfig(emptySet(), emptySet())))
        assertContentEquals(listOf("TODO", "DONE"), items.map { it.label })
    }

    @Test
    fun acceptingAKeywordLeavesTheCaretReadyForTheTitle() {
        val fixture = "* TO‸"
        val item = completeAt(fixture).single { it.label == "TODO" }
        assertEquals("* TODO ‸", apply(fixture, item))
    }

    // --- Row 2: the planning line under a heading -------------------------------------------------

    @Test
    fun offersPlanningLinesUnderAHeading() {
        assertContentEquals(
            listOf("SCHEDULED: <2026-09-23 Wed>"),
            completeAt("* Buy milk\nSCHE‸").map { it.label },
        )
        assertContentEquals(
            listOf("DEADLINE: <2026-09-23 Wed>"),
            completeAt("* Buy milk\nDEAD‸").map { it.label },
        )
    }

    @Test
    fun doesNotOfferPlanningLinesAwayFromAHeading() {
        // Same word, but the line above is body text rather than a heading.
        assertTrue(completeAt("* Buy milk\nsome notes\nSCHE‸").isEmpty())
    }

    // --- Row 3: dates after SCHEDULED:/DEADLINE: ---------------------------------------------------

    @Test
    fun offersDatesAfterAPlanningKeyword() {
        val labels = completeAt("* Buy milk\nSCHEDULED: ‸").map { it.label }
        // The picker leads: behind ten dates, a phone's strip would never show it.
        assertEquals("Pick date & time…", labels.first())
        assertTrue(labels[1].startsWith("today — <2026-09-23 Wed>"), labels[1])
        assertTrue(labels.any { it.startsWith("tomorrow — <2026-09-24 Thu>") })
        assertTrue(labels.any { it.startsWith("+1w — <2026-09-30 Wed>") })
        assertTrue(labels.any { it.startsWith("Friday — <2026-09-25 Fri>") })
    }

    @Test
    fun aTypedWeekdayNarrowsTheDates() {
        val items = completeAt("* Buy milk\nSCHEDULED: fri‸")
        assertContentEquals(listOf("Friday — <2026-09-25 Fri>", "Pick date & time…"), items.map { it.label })
        // And it replaces what was typed rather than appending to it.
        assertEquals(
            "* Buy milk\nSCHEDULED: <2026-09-25 Fri>‸",
            apply("* Buy milk\nSCHEDULED: fri‸", items.first()),
        )
    }

    @Test
    fun theDatePickerEntryInsertsNothingByItself() {
        val picker = completeAt("* Buy milk\nDEADLINE: ‸").single { it.kind == CompletionItem.Kind.DATE_PICKER }
        assertEquals("", picker.insert)
        // What the picker comes back with goes in its place, caret after it.
        val result = picker.applyTo("* Buy milk\nDEADLINE: ", "<2026-12-24 Thu>")
        assertEquals("* Buy milk\nDEADLINE: <2026-12-24 Thu>", result.text)
        assertEquals(result.text.length, result.caret)
    }

    // --- Row 3b: a planning stamp already written -------------------------------------------------

    @Test
    fun acceptingAPlanningLineLeavesThePickerOnOffer() {
        // Where the planning completion leaves the caret: after the stamp it wrote.
        val items = completeAt("* Buy milk\nSCHEDULED: <2026-09-23 Wed>‸")
        val picker = items.first()
        assertEquals(CompletionItem.Kind.DATE_PICKER, picker.kind)
        // It stands in for the whole stamp, so what the picker returns replaces it.
        val result = picker.applyTo("* Buy milk\nSCHEDULED: <2026-09-23 Wed>", "<2026-12-24 Thu 10:00>")
        assertEquals("* Buy milk\nSCHEDULED: <2026-12-24 Thu 10:00>", result.text)
    }

    @Test
    fun theCaretInsideAStampOffersToChangeAllOfIt() {
        val items = completeAt("* Buy milk\nDEADLINE: <2026-09-2‸3 Wed 10:00 +1w -2d>")
        assertEquals(CompletionItem.Kind.DATE_PICKER, items.first().kind)
        // A shortcut moves the date but keeps the time, the repeater and the warning.
        val tomorrow = items.single { it.label.startsWith("tomorrow") }
        assertEquals(
            "* Buy milk\nDEADLINE: <2026-09-24 Thu 10:00 +1w -2d>‸",
            apply("* Buy milk\nDEADLINE: <2026-09-2‸3 Wed 10:00 +1w -2d>", tomorrow),
        )
    }

    @Test
    fun theStampUnderTheCaretIsTheOneChanged() {
        val fixture = "* Buy milk\nSCHEDULED: <2026-09-23 Wed> DEADLINE: <2026-09-30 Wed>‸"
        val today = completeAt(fixture).single { it.label.startsWith("today") }
        assertEquals("* Buy milk\nSCHEDULED: <2026-09-23 Wed> DEADLINE: <2026-09-23 Wed>‸", apply(fixture, today))
    }

    @Test
    fun aStampOutsideAPlanningLineIsLeftAlone() {
        assertTrue(completeAt("* Buy milk\nCall the shop <2026-09-23 Wed>‸").isEmpty())
        assertTrue(completeAt("* DONE Buy milk\nCLOSED: [2026-09-23 Wed 10:00]‸").isEmpty())
    }

    @Test
    fun restampKeepsWhatThePickerDoesNotAskAbout() {
        val date = LocalDate(2026, 12, 24)
        assertEquals("<2026-12-24 Thu 09:30 .+1d>", restamp("<2026-09-23 Wed 10:00 .+1d>", date, LocalTime(9, 30)))
        assertEquals("<2026-12-24 Thu ++1w>", restamp("<2026-09-23 Wed 10:00 ++1w>", date, null))
        assertEquals("[2026-12-24 Thu]", restamp("[2026-09-23 Wed]", date, null))
        // Not a stamp: a new one.
        assertEquals("<2026-12-24 Thu 08:05>", restamp("fri", date, LocalTime(8, 5)))
    }

    @Test
    fun restampWritesARangeWithinTheDay() {
        val date = LocalDate(2026, 12, 24)
        assertEquals(
            "<2026-12-24 Thu 14:00-15:30 +1w>",
            restamp("<2026-09-23 Wed 10:00 +1w>", date, LocalTime(14, 0), LocalTime(15, 30)),
        )
        // An end with no start, or not after it, is not a range.
        assertEquals("<2026-12-24 Thu>", restamp("<2026-09-23 Wed 10:00-11:00>", date, null, LocalTime(11, 0)))
        assertEquals("<2026-12-24 Thu 14:00>", restamp("<2026-09-23 Wed>", date, LocalTime(14, 0), LocalTime(13, 0)))
    }

    @Test
    fun aMovedRangeKeepsItsLength() {
        assertEquals(LocalTime(15, 30), shiftedEnd(LocalTime(10, 0), LocalTime(11, 30), LocalTime(14, 0)))
        // Not a range, or one that would run into tomorrow: no end.
        assertEquals(null, shiftedEnd(LocalTime(10, 0), null, LocalTime(14, 0)))
        assertEquals(null, shiftedEnd(LocalTime(10, 0), LocalTime(12, 0), LocalTime(23, 0)))
    }

    @Test
    fun aShortcutKeepsTheRangeOfTheStampItChanges() {
        val fixture = "* Standup\nSCHEDULED: <2026-09-23 Wed 09:30-09:45>‸"
        val tomorrow = completeAt(fixture).single { it.label.startsWith("tomorrow") }
        assertEquals("* Standup\nSCHEDULED: <2026-09-24 Thu 09:30-09:45>‸", apply(fixture, tomorrow))
    }

    // --- Row 4: tags at the end of a heading --------------------------------------------------------

    @Test
    fun offersTagsOnAHeadingMostUsedFirst() {
        assertContentEquals(listOf(":work:", ":home:", ":wishlist:"), completeAt("* Buy milk :‸").map { it.label })
    }

    @Test
    fun aTypedTagPrefixNarrowsAndReplacesWhatWasTyped() {
        val fixture = "* Buy milk :wi‸"
        val items = completeAt(fixture)
        assertContentEquals(listOf(":wishlist:"), items.map { it.label })
        assertEquals("* Buy milk :wishlist:‸", apply(fixture, items.single()))
    }

    @Test
    fun doesNotOfferTagsOutsideAHeading() {
        assertTrue(completeAt("Some body text :‸").isEmpty())
    }

    // --- Row 5: #+ keywords at the start of a line ---------------------------------------------------

    @Test
    fun offersFileKeywords() {
        val labels = completeAt("#+‸").map { it.label }
        assertEquals("#+TITLE:", labels.first())
        assertTrue(labels.containsAll(listOf("#+FILETAGS:", "#+TODO:", "#+STARTUP:")))
    }

    @Test
    fun aKeywordInsertsItsColonAndASpace() {
        val fixture = "#+TIT‸"
        val item = completeAt(fixture).single()
        assertEquals("#+TITLE: ‸", apply(fixture, item))
    }

    /** `#+BEGIN_SRC` is a block, so it comes with its end line and the caret between them. */
    @Test
    fun aBeginKeywordInsertsTheWholeBlock() {
        val fixture = "#+BEGIN_S‸"
        val item = completeAt(fixture).single()
        assertEquals("#+BEGIN_SRC\n‸\n#+END_SRC", apply(fixture, item))
    }

    // --- Row 6: links ---------------------------------------------------------------------------------

    @Test
    fun offersNotesThenHeadingsForALink() {
        val labels = completeAt("See [[‸").map { it.label }
        assertContentEquals(
            listOf("notes/groceries.org", "projects/korgnote.org", "*Shopping", "*Reading"),
            labels,
        )
    }

    @Test
    fun aTypedLinkPrefixNarrowsAcrossBothKinds() {
        assertContentEquals(listOf("notes/groceries.org"), completeAt("See [[groc‸").map { it.label })
        assertContentEquals(listOf("*Shopping"), completeAt("See [[shop‸").map { it.label })
    }

    @Test
    fun acceptingALinkReplacesTheOpeningBrackets() {
        val fixture = "See [[groc‸"
        val item = completeAt(fixture).single()
        assertEquals("See [[file:notes/groceries.org][groceries]]‸", apply(fixture, item))
    }

    /** Org reads a relative `file:` link from the linking note's folder, so it is written from there. */
    @Test
    fun aFileLinkIsWrittenRelativeToTheNoteBeingEdited() {
        val fromDaily = ctx.copy(currentPath = "daily/2026-09-24.org")
        val item = completeAt("See [[groc‸", fromDaily).single()
        assertEquals("[[file:../notes/groceries.org][groceries]]", item.insert)

        val sameFolder = ctx.copy(currentPath = "notes/inbox.org")
        assertEquals("[[file:groceries.org][groceries]]", completeAt("See [[groc‸", sameFolder).single().insert)
    }

    /** A note with its own `:ID:` is linked the org-roam way, which survives a rename. */
    @Test
    fun aNoteWithAnIdIsLinkedByIdAndNamedByItsTitle() {
        val withId = ctx.copy(
            noteIds = mapOf("notes/groceries.org" to "abc-123"),
            noteTitles = mapOf("notes/groceries.org" to "Groceries"),
        )
        assertEquals("[[id:abc-123][Groceries]]", completeAt("See [[groc‸", withId).single().insert)
    }

    @Test
    fun offersNothingAfterAFinishedLink() {
        assertTrue(completeAt("See [[file:notes/groceries.org]] ‸").isEmpty())
    }

    // --- Row 7: timestamps ------------------------------------------------------------------------------

    @Test
    fun offersTodayForAnAngleBracket() {
        val fixture = "Met them on <‸"
        val item = completeAt(fixture).single()
        assertEquals("<2026-09-23 Wed>", item.label)
        assertEquals("Met them on <2026-09-23 Wed>‸", apply(fixture, item))
    }

    @Test
    fun aSquareBracketGivesTheInactiveForm() {
        val fixture = "Met them on [‸"
        val item = completeAt(fixture).single()
        assertEquals("Met them on [2026-09-23 Wed]‸", apply(fixture, item))
    }

    // --- Row 8: org-tempo structure templates -------------------------------------------------------------

    @Test
    fun expandsStructureTemplates() {
        assertEquals("#+BEGIN_SRC\n‸\n#+END_SRC", apply("<s‸", completeAt("<s‸").single()))
        assertEquals("#+BEGIN_QUOTE\n‸\n#+END_QUOTE", apply("<q‸", completeAt("<q‸").single()))
        assertEquals("#+BEGIN_EXAMPLE\n‸\n#+END_EXAMPLE", apply("<e‸", completeAt("<e‸").single()))
    }

    /** `<s` is a template only at the start of a line; mid-sentence it is a timestamp. */
    @Test
    fun aTemplateLetterMidLineIsNotATemplate() {
        assertContentEquals(listOf("<2026-09-23 Wed>"), completeAt("Met them on <‸").map { it.label })
    }

    // --- What must not fire --------------------------------------------------------------------------------

    @Test
    fun offersNothingInsideASourceBlock() {
        // A `[[` inside code is not a link, and a `<s` inside code is not a template.
        assertTrue(completeAt("#+BEGIN_SRC sh\nls [[‸\n#+END_SRC").isEmpty())
        assertTrue(completeAt("#+BEGIN_SRC sh\n<s‸\n#+END_SRC").isEmpty())
    }

    @Test
    fun offersNothingForOrdinaryProse() {
        assertTrue(completeAt("Just some ordinary words‸").isEmpty())
    }

    @Test
    fun aCursorOutsideTheTextIsNotACrash() {
        val doc = "* Buy milk\n".toOrgDocument()
        assertTrue(complete(doc, -1, ctx).isEmpty())
        assertTrue(complete(doc, 9_999, ctx).isEmpty())
    }
}
