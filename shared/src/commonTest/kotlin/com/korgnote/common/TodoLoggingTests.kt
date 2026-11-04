package com.korgnote.common

import com.korgnote.common.org.KeywordLogging
import com.korgnote.common.org.LogKind
import com.korgnote.common.org.OrgDocument
import com.korgnote.common.org.OrgSection
import com.korgnote.common.org.edit.allNodes
import com.korgnote.common.org.edit.changeTodoState
import com.korgnote.common.org.edit.stateChangeLog
import com.korgnote.common.org.toOrgDocument
import com.korgnote.common.org.todoLoggingOf
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Logging a state change as each keyword asks (Org manual 5.3.2, "Tracking TODO state changes"):
 * `!` a time stamp, `@` a note, on entering the state, or after `/` on leaving it.
 */
class TodoLoggingTests {

    private val keywords = """
        #+TODO: TODO(t) DOING(p!) WAIT(w@/!) | DONE(d!) CANCELED(c@/!)
        #+TODO: EVENT(e) | FINISHED(f)
    """.trimIndent()

    private val at = LocalDateTime(2026, 11, 1, 10, 30)
    private val stamp = "[2026-11-01 Sun 10:30]"

    private fun OrgDocument.heading() = allNodes().filterIsInstance<OrgSection>().first()

    private fun changed(text: String, to: String?, note: String? = null): String {
        val doc = text.toOrgDocument()
        return doc.changeTodoState(doc.heading(), to, at, note = note).text
    }

    private fun logFor(text: String, to: String?): LogKind? {
        val doc = text.toOrgDocument()
        return doc.stateChangeLog(doc.heading(), to)
    }

    // --- Reading the settings ---

    @Test
    fun eachKeywordSaysWhatItLogs() {
        val logging = todoLoggingOf(keywords)
        assertEquals(
            mapOf(
                "DOING" to KeywordLogging(LogKind.Time, null),
                "WAIT" to KeywordLogging(LogKind.Note, LogKind.Time),
                "DONE" to KeywordLogging(LogKind.Time, null),
                "CANCELED" to KeywordLogging(LogKind.Note, LogKind.Time),
            ),
            logging.keywords,
            "TODO, EVENT and FINISHED log nothing",
        )
        assertNull(logging.done, "no CLOSED stamp unless the file asks")
        assertEquals(LogKind.Time, logging.repeat, "a repeat is recorded, as org-log-repeat says")
    }

    @Test
    fun startupOptionsChangeTheDefaults() {
        val logging = todoLoggingOf("#+STARTUP: lognotedone nologrepeat logdrawer\n")
        assertEquals(LogKind.Note, logging.done)
        assertNull(logging.repeat)
        assertEquals(true, logging.intoDrawer)
        assertEquals(LogKind.Time, todoLoggingOf("#+STARTUP: logdone").done)
        assertEquals(LogKind.Note, todoLoggingOf("#+STARTUP: lognoterepeat").repeat)
    }

    // --- What is logged ---

    @Test
    fun aKeywordWithoutASettingLogsNothing() {
        assertEquals("$keywords\n* EVENT Party\n", changed("$keywords\n* TODO Party\n", "EVENT"))
        assertEquals("* DONE Task\n", changed("* TODO Task\n", "DONE"), "no #+TODO line: Org's TODO and DONE, which log nothing")
        assertEquals("#+TODO: TODO | DONE\n* DONE Task\n", changed("#+TODO: TODO | DONE\n* TODO Task\n", "DONE"))
    }

    @Test
    fun anExclamationMarkLogsATimeStamp() {
        assertEquals(
            "$keywords\n* DOING Write\n- State \"DOING\"      from \"TODO\"       $stamp\n",
            changed("$keywords\n* TODO Write\n", "DOING"),
        )
    }

    @Test
    fun anAtSignAsksForANoteAndLogsIt() {
        val text = "$keywords\n* TODO Call the plumber\n"
        assertEquals(LogKind.Note, logFor(text, "WAIT"))
        assertEquals(
            "$keywords\n* WAIT Call the plumber\n" +
                "- State \"WAIT\"       from \"TODO\"       $stamp \\\\\n" +
                "  Waiting for his quote.\n" +
                "\n" +
                "  Call back on Monday.\n",
            changed(text, "WAIT", note = "Waiting for his quote.\n\nCall back on Monday.\n"),
        )
    }

    @Test
    fun aBlankNoteLeavesTheStateLineAlone() {
        assertEquals(
            "$keywords\n* CANCELED Trip\n- State \"CANCELED\"   from \"TODO\"       $stamp\n",
            changed("$keywords\n* TODO Trip\n", "CANCELED", note = "  \n"),
        )
    }

    @Test
    fun leavingAStateLogsWhenTheNewOneDoesNot() {
        // WAIT(w@/!) to TODO, which logs nothing itself: WAIT's /! stamps it.
        val text = "$keywords\n* WAIT Call\n"
        assertEquals(LogKind.Time, logFor(text, "TODO"))
        assertEquals("$keywords\n* TODO Call\n- State \"TODO\"       from \"WAIT\"       $stamp\n", changed(text, "TODO"))
        // To DONE(d!), which logs itself: its own setting, a time stamp, not a note.
        assertEquals(LogKind.Time, logFor(text, "DONE"))
        // CANCELED(c@/!) has its own @: a note, whatever WAIT says about leaving.
        assertEquals(LogKind.Note, logFor(text, "CANCELED"))
    }

    @Test
    fun clearingTheKeywordLogsNothing() {
        assertNull(logFor("$keywords\n* WAIT Call\n", null))
        assertEquals("$keywords\n* Call\n", changed("$keywords\n* WAIT Call\n", null))
    }

    // --- CLOSED ---

    @Test
    fun logdoneStampsClosedFirstOnThePlanningLine() {
        val text = "#+STARTUP: logdone\n* TODO Task\nSCHEDULED: <2026-11-01 Sun>\n"
        assertEquals("#+STARTUP: logdone\n* DONE Task\nCLOSED: $stamp SCHEDULED: <2026-11-01 Sun>\n", changed(text, "DONE"))
        assertEquals("#+STARTUP: logdone\n* DONE Task\nCLOSED: $stamp\nBody.\n", changed("#+STARTUP: logdone\n* TODO Task\nBody.\n", "DONE"))
    }

    @Test
    fun reopenedItLosesItsClosedStamp() {
        val text = "#+STARTUP: logdone\n* DONE Task\nCLOSED: [2026-10-01 Thu 09:00] SCHEDULED: <2026-11-01 Sun>\n"
        assertEquals("#+STARTUP: logdone\n* TODO Task\nSCHEDULED: <2026-11-01 Sun>\n", changed(text, "TODO"))
        assertEquals("#+STARTUP: logdone\n* TODO Task\n", changed("#+STARTUP: logdone\n* DONE Task\nCLOSED: [2026-10-01 Thu 09:00]\n", "TODO"))
    }

    @Test
    fun withNothingTrackedAClosedStampIsLeftAlone() {
        val text = "* DONE Task\nCLOSED: [2026-10-01 Thu 09:00]\n"
        assertEquals("* TODO Task\nCLOSED: [2026-10-01 Thu 09:00]\n", changed(text, "TODO"))
    }

    @Test
    fun lognotedoneAsksForAClosingNote() {
        val text = "#+STARTUP: lognotedone\n* TODO Task\n"
        assertEquals(LogKind.Note, logFor(text, "DONE"))
        assertEquals(
            "#+STARTUP: lognotedone\n* DONE Task\nCLOSED: $stamp\n- CLOSING NOTE $stamp \\\\\n  Shipped.\n",
            changed(text, "DONE", note = "Shipped."),
        )
    }

    // --- Repeating entries ---

    @Test
    fun aRepeatIsRecordedByDefault() {
        val result = changed("* TODO Water\nSCHEDULED: <2026-11-01 Sun +1w>\n", "DONE")
        assertEquals(
            "* TODO Water\nSCHEDULED: <2026-11-08 Sun +1w>\n:PROPERTIES:\n:LAST_REPEAT: $stamp\n:END:\n" +
                "- State \"DONE\"       from \"TODO\"       $stamp\n",
            result,
        )
    }

    @Test
    fun nologrepeatRecordsNothing() {
        val result = changed("#+STARTUP: nologrepeat\n* TODO Water\nSCHEDULED: <2026-11-01 Sun +1w>\n", "DONE")
        assertEquals("#+STARTUP: nologrepeat\n* TODO Water\nSCHEDULED: <2026-11-08 Sun +1w>\n", result)
    }

    @Test
    fun aRepeatTakesANoteWhenTheDoneStateOrTheFileAsks() {
        val repeating = "* TODO Water\nSCHEDULED: <2026-11-01 Sun +1w>\n"
        assertEquals(LogKind.Note, logFor("#+TODO: TODO | DONE(d@)\n$repeating", "DONE"))
        assertEquals(LogKind.Note, logFor("#+STARTUP: lognoterepeat\n$repeating", "DONE"))
        val result = changed("#+TODO: TODO | DONE(d@)\n$repeating", "DONE", note = "Used the rain water.")
        assertEquals(true, result.endsWith("- State \"DONE\"       from \"TODO\"       $stamp \\\\\n  Used the rain water.\n"), result)
    }

    /** Org reads `#+STARTUP:` in order, so the option written last wins. */
    @Test
    fun nologdrawerTurnsTheDrawerOffAgain() {
        val text = "#+TODO: TODO WAIT(w!) | DONE\n#+STARTUP: logdrawer\n#+STARTUP: nologdrawer\n* TODO Task\n"
        assertEquals(
            "#+TODO: TODO WAIT(w!) | DONE\n#+STARTUP: logdrawer\n#+STARTUP: nologdrawer\n* WAIT Task\n" +
                "- State \"WAIT\"       from \"TODO\"       $stamp\n",
            changed(text, "WAIT"),
        )
    }

    /** A note about Org may show `#+STARTUP:` in a block, where it is code and sets nothing. */
    @Test
    fun aStartupLineInsideABlockSetsNothing() {
        val block = "#+BEGIN_SRC org\n#+STARTUP: logdrawer logdone\n#+END_SRC\n"
        val logging = todoLoggingOf(block)
        assertEquals(false, logging.intoDrawer)
        assertNull(logging.done)
        assertEquals(
            "#+TODO: TODO WAIT(w!) | DONE\n$block* WAIT Task\n- State \"WAIT\"       from \"TODO\"       $stamp\n",
            changed("#+TODO: TODO WAIT(w!) | DONE\n$block* TODO Task\n", "WAIT"),
        )
    }

    /** Another block's `#+END_` does not close it, as it does not for the parser. */
    @Test
    fun aStartupLineAfterAnotherBlocksEndStillSetsNothing() {
        assertEquals(false, todoLoggingOf("#+BEGIN_SRC org\n#+END_QUOTE\n#+STARTUP: logdrawer\n#+END_SRC\n").intoDrawer)
    }

    @Test
    fun aStartupLineAfterABlockStillCounts() {
        assertEquals(true, todoLoggingOf("#+BEGIN_SRC org\n#+END_SRC\n#+STARTUP: logdrawer\n").intoDrawer)
    }
}
