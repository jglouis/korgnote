package com.korgnote.common

import com.korgnote.common.index.factsOf
import com.korgnote.common.org.agenda.AgendaFilter
import com.korgnote.common.org.agenda.AgendaIndex
import com.korgnote.common.org.agenda.AgendaOptions
import com.korgnote.common.org.agenda.grouped
import com.korgnote.common.org.agenda.openTodos
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TodoListTests {
    private val tasks = """
        #+TODO: TODO NEXT WAITING | DONE CANCELLED
        #+FILETAGS: :garden:
        * TODO Buy seeds
        * NEXT Dig the beds :outside:
        SCHEDULED: <2030-03-02 Sat> DEADLINE: <2030-03-09 Sat>
        * DONE Order compost
        * CANCELLED Rent a tiller
        * Ideas
        ** WAITING Neighbour's ladder
        ** TODO [#A] Fix the fence
        * COMMENT Old plans
        ** TODO Plant roses
        * Archived :ARCHIVE:
        ** TODO Plant tulips
        * TODO Water <2030-03-05 Tue>
        * TODO Mulch
        SCHEDULED: [2030-03-04 Mon]
    """.trimIndent()

    @Test
    fun everyHeadingStillToDoIsListedDatedOrNot() {
        val todos = openTodos("garden.org", tasks)
        assertEquals(
            listOf("Buy seeds", "Dig the beds", "Neighbour's ladder", "Fix the fence", "Water", "Mulch"),
            todos.map { it.title },
        )
        assertEquals(listOf("TODO", "NEXT", "WAITING", "TODO", "TODO", "TODO"), todos.map { it.todo })
        assertEquals(listOf(0, 1, 2, 0, 0, 0), todos.map { it.keywordOrder })
    }

    @Test
    fun aTodoKnowsItsDatesTagsAndPriority() {
        val todos = openTodos("garden.org", tasks).associateBy { it.title }
        val dig = todos.getValue("Dig the beds")
        assertEquals(LocalDate(2030, 3, 2), dig.scheduled)
        assertEquals(LocalDate(2030, 3, 9), dig.deadline)
        assertEquals(LocalDate(2030, 3, 9), dig.due)
        assertEquals(listOf("outside", "garden"), dig.tags)
        assertEquals("A", todos.getValue("Fix the fence").priority?.text)
        assertNull(todos.getValue("Buy seeds").due)
        // An inactive stamp after SCHEDULED is no date, as in the agenda.
        assertNull(todos.getValue("Mulch").scheduled)
        // The heading's own stamp is an appointment, not when it is due, and is not part of its title.
        assertNull(todos.getValue("Water").due)
        assertEquals(tasks.indexOf("* NEXT Dig"), dig.headingOffset)
    }

    @Test
    fun aNoteWithoutTasksHasNone() {
        assertTrue(openTodos("plain.org", "* Groceries\nMilk, eggs.\n").isEmpty())
        // Without a #+TODO: line, Org's own TODO and DONE.
        assertEquals(listOf("Call back"), openTodos("x.org", "* TODO Call back\n* DONE Called\n* NEXT Not a keyword here\n").map { it.title })
    }

    @Test
    fun theListIsGroupedByKeywordInTheOrderTheyAreDeclared() {
        val groups = openTodos("garden.org", tasks).grouped()
        assertEquals(listOf("TODO", "NEXT", "WAITING"), groups.map { it.keyword })
        // Priority A first, then the rest by when they are due and where they are.
        assertEquals(listOf("Fix the fence", "Buy seeds", "Water", "Mulch"), groups.first().todos.map { it.title })
    }

    @Test
    fun theFilterNarrowsByKeywordOrTagAndBoth() {
        val index = AgendaIndex.of(mapOf("garden.org" to tasks), AgendaOptions(today = LocalDate(2030, 3, 1)))
        assertSame(index, index.filtered(AgendaFilter.NONE))

        val next = index.filtered(AgendaFilter(keywords = setOf("NEXT", "WAITING")))
        assertEquals(listOf("Dig the beds", "Neighbour's ladder"), next.todos.map { it.title })
        assertTrue(next.entries.all { it.todo == "NEXT" })

        val outside = index.filtered(AgendaFilter(tags = setOf("OUTSIDE")))
        assertEquals(listOf("Dig the beds"), outside.todos.map { it.title })

        // Within a group one is enough; between groups both are needed.
        val both = index.filtered(AgendaFilter(keywords = setOf("TODO"), tags = setOf("outside", "garden")))
        assertEquals(listOf("Buy seeds", "Fix the fence", "Water", "Mulch"), both.todos.map { it.title })
        assertEquals(listOf("Water", "Mulch"), both.entries.map { it.title }, "Mulch's inactive stamp is an entry the calendar does not show")
    }

    @Test
    fun togglingAddsAndTakesAway() {
        val filter = AgendaFilter.NONE.toggleKeyword("NEXT").toggleTag("Work")
        assertEquals(AgendaFilter(setOf("NEXT"), setOf("Work")), filter)
        assertEquals(AgendaFilter.NONE, filter.toggleKeyword("NEXT").toggleTag("work"))
    }

    @Test
    fun aNoteWithTasksButNoDatesIsKeptWithTheCalendarsPart() {
        val facts = factsOf("x.org", "* TODO Call back\n")
        assertEquals(listOf("Call back"), facts.todos.map { it.title })
        assertTrue(facts.isDated, "the TODO list has to be there as soon as the app opens, as the calendar is")
    }
}
