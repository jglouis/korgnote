package com.korgnote.common

import com.korgnote.common.index.IndexPart
import com.korgnote.common.index.LeftOut
import com.korgnote.common.index.NoteBudgets
import com.korgnote.common.index.NoteFacts
import com.korgnote.common.index.NoteIndex
import com.korgnote.common.index.NoteIndexFile
import com.korgnote.common.index.SizedCache
import com.korgnote.common.index.factsOf
import com.korgnote.common.org.agenda.agendaEntries
import com.korgnote.common.org.agenda.clockedSpans
import com.korgnote.common.org.link.linkTargetsFrom
import com.korgnote.common.org.link.linkTargetsOf
import com.korgnote.common.org.toOrgDocument
import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.FileStamp
import com.korgnote.common.replica.InMemoryReplicaStorage
import com.korgnote.common.replica.ReplicaStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A file that is only ever in memory. */
private class MemoryIndexFile : NoteIndexFile {
    val parts = mutableMapOf<IndexPart, List<String>>()

    override suspend fun read(part: IndexPart, line: (String) -> Unit) = parts[part].orEmpty().forEach(line)

    override suspend fun write(part: IndexPart, lines: Sequence<String>) {
        parts[part] = lines.toList()
    }
}

private val roomy = NoteBudgets(texts = 1L shl 30, parses = 1L shl 30, folderTexts = 1L shl 30, facts = 1L shl 30, maxNoteBytes = 1L shl 30)

/** Notes with a bit of everything the facts are about. */
private val notes = mapOf(
    "a.org" to """
        :PROPERTIES:
        :ID:       note-a
        :END:
        #+TITLE: Note A
        #+FILETAGS: :project:
        #+TODO: NEXT | FINISHED

        * NEXT First [1/2] :work:
        SCHEDULED: <2026-11-05 Thu 14:00>
        :PROPERTIES:
        :ID:       heading-a
        :LOCATION: Paris
        :END:
        See [[id:note-b][B]], [[file:b.org::*Second][a search]], [[Second heading]] and [[https://example.invalid]].
        ** FINISHED [#A] Sub [[id:note-b][linked]] :deep:
        DEADLINE: <2026-11-07 Sat -3d>
        * Second heading
        :LOGBOOK:
        CLOCK: [2026-11-01 Sun 10:00]--[2026-11-01 Sun 11:30] =>  1:30
        :END:
        * Clocked now
        :LOGBOOK:
        CLOCK: [2026-11-05 Thu 08:00]
        :END:
    """.trimIndent() + "\n",
    "sub/b.org" to """
        #+LINK: gh https://example.invalid/%s
        Above any heading [[../a.org]]
        * TODO Élégant héading ✓ :work:home:
        <2026-11-05 Thu>
        - [[stray text]] and [[gh:thing]]
        #+begin_src org
        * Not a heading
        SCHEDULED: <2026-11-09 Mon>
        #+end_src
        * Third
        ** Fourth
        *** Fifth
        ** Sixth
    """.trimIndent() + "\n",
    "c.org" to "No headings, no title, nothing.\n",
    "empty.org" to "",
)

class NoteIndexTest {

    private fun allFacts() = notes.mapValues { (path, text) -> factsOf(path, text) }

    /** What the pickers show has to be what they showed when every note was parsed and kept. */
    @Test
    fun thePickersGetTheSameTargetsFromTheFacts() {
        assertEquals(linkTargetsOf(notes.mapValues { it.value.toOrgDocument() }), linkTargetsFrom(allFacts()))
    }

    @Test
    fun theCalendarGetsTheSameEntriesFromTheFacts() {
        val facts = allFacts()
        assertEquals(agendaEntries(notes), facts.keys.sorted().flatMap { facts.getValue(it).agenda })
        assertEquals(notes.flatMap { (path, text) -> clockedSpans(path, text) }.toSet(), facts.values.flatMap { it.clocked }.toSet())
    }

    @Test
    fun theFactsHoldWhatCompletionAndTheClocksNeed() {
        val a = factsOf("a.org", notes.getValue("a.org"))
        assertEquals("Note A", a.title)
        assertEquals("note-a", a.fileId)
        assertEquals(mapOf("project" to 1, "work" to 1, "deep" to 1), a.tags)
        assertEquals(mapOf("Paris" to 1), a.locations)
        assertEquals(setOf("NEXT"), a.keywords.todo)
        assertEquals(listOf("Clocked now"), a.running.map { it.title })
        assertEquals(listOf("Second heading"), a.lastClocked.map { it.title })
        // Only what may lead to a note: not the web link.
        assertEquals(listOf("id:note-b", "file:b.org::*Second", "Second heading", "id:note-b"), a.links.map { it.target })
        assertEquals(listOf(false, false, true, false), a.links.map { it.withinNote })
        assertNull(factsOf("c.org", notes.getValue("c.org")).todoKeywords)
    }

    /** A generated log can have tens of thousands of headings, each held in memory all the time. */
    @Test
    fun aNoteOffersOnlySoManyHeadingsToThePickers() {
        val text = (1..NoteFacts.MAX_HEADINGS + 50).joinToString("") { "* Heading $it\n" }
        assertEquals(NoteFacts.MAX_HEADINGS, factsOf("log.org", text).headings.size)
    }

    @Test
    fun whatWasKeptIsWhatComesBack() = runTest {
        val file = MemoryIndexFile()
        val store = DocumentStore(InMemoryReplicaStorage(notes))
        NoteIndex(file, roomy).refresh(store, Dispatchers.Unconfined)

        val again = NoteIndex(file, roomy)
        again.load()

        assertEquals(allFacts(), again.facts)
    }

    @Test
    fun aNoteWhoseFileDidNotMoveIsNotReadAgain() = runTest {
        val file = MemoryIndexFile()
        val stamps = notes.mapValues { (_, text) -> FileStamp(1, text.length.toLong()) }
        val first = NoteIndex(file, roomy)
        first.scan(first.staleAmong(stamps), stamps, Dispatchers.Unconfined, read = { paths -> notes.filterKeys { it in paths } })

        val again = NoteIndex(file, roomy)
        again.load()
        assertTrue(again.staleAmong(stamps).isEmpty(), "nothing moved")

        val moved = stamps + ("a.org" to FileStamp(2, 1))
        assertEquals(listOf("a.org"), again.staleAmong(moved))
    }

    /** The calendar opens on what is dated, without waiting for a great many headings it has no use for. */
    @Test
    fun whatIsDatedIsReadFirstAndAlone() = runTest {
        val file = MemoryIndexFile()
        NoteIndex(file, roomy).refresh(DocumentStore(InMemoryReplicaStorage(notes)), Dispatchers.Unconfined)

        val again = NoteIndex(file, roomy)
        again.loadDated()
        assertEquals(setOf("a.org", "sub/b.org"), again.facts.keys)

        again.load()
        assertEquals(notes.keys, again.facts.keys)
        assertEquals(allFacts(), again.facts)
    }

    /** Reading what was kept is not a change to it: it is not written straight back. */
    @Test
    fun loadingLeavesNothingToSave() = runTest {
        val file = MemoryIndexFile()
        NoteIndex(file, roomy).refresh(DocumentStore(InMemoryReplicaStorage(notes)), Dispatchers.Unconfined)
        file.parts[IndexPart.Rest] = file.parts.getValue(IndexPart.Rest) + "{ damaged, and left for a rescan to put right"
        val before = file.parts.toMap()

        val again = NoteIndex(file, roomy)
        again.load()
        again.save()

        assertEquals(before, file.parts.toMap())
    }

    /** It is only a cache, and whatever is wrong with it costs a scan. */
    @Test
    fun aDamagedFileOnlyCostsAScan() = runTest {
        val file = MemoryIndexFile()
        NoteIndex(file, roomy).refresh(DocumentStore(InMemoryReplicaStorage(notes)), Dispatchers.Unconfined)
        val good = file.parts.toMap()

        file.parts[IndexPart.Dated] = good.getValue(IndexPart.Dated).let { it.take(2) + "{ this is not json" + it.drop(2) }
        val damaged = NoteIndex(file, roomy)
        damaged.load()
        assertEquals(notes.size, damaged.facts.size, "every good line is read")

        file.parts[IndexPart.Dated] = good.getValue(IndexPart.Dated).drop(1)
        file.parts[IndexPart.Rest] = good.getValue(IndexPart.Rest).drop(1) + "korgnote-index-1"
        val noHeader = NoteIndex(file, roomy)
        noHeader.load()
        assertTrue(noHeader.facts.isEmpty(), "what does not say what it is is not trusted")

        file.parts[IndexPart.Dated] = listOf("korgnote-index-0") + good.getValue(IndexPart.Dated).drop(1)
        file.parts[IndexPart.Rest] = listOf("korgnote-index-0") + good.getValue(IndexPart.Rest).drop(1)
        val older = NoteIndex(file, roomy)
        older.load()
        assertTrue(older.facts.isEmpty(), "a parser that has changed finds other things")
    }

    /** The path each entry repeats is most of it on disk, and it has to come back as it was. */
    @Test
    fun theFileDoesNotRepeatThePathInEveryEntry() = runTest {
        val file = MemoryIndexFile()
        val note = "* One\n<2026-11-05 Thu>\n* Two\n<2026-11-06 Fri>\n"
        val index = NoteIndex(file, roomy)
        index.refresh(DocumentStore(InMemoryReplicaStorage(mapOf("a-rather-long-name.org" to note))), Dispatchers.Unconfined)

        assertEquals(1, file.parts.getValue(IndexPart.Dated).single { "One" in it }.split("a-rather-long-name.org").size - 1)
        val again = NoteIndex(file, roomy)
        again.load()
        assertEquals(setOf("a-rather-long-name.org"), again.facts.getValue("a-rather-long-name.org").agenda.map { it.file }.toSet())
    }

    @Test
    fun aNoteTooBigToHoldIsNeverRead() = runTest {
        val budgets = roomy.copy(maxNoteBytes = 100)
        val index = NoteIndex(budgets = budgets)
        val stamps = mapOf("small.org" to FileStamp(1, 50), "huge.org" to FileStamp(1, 101))
        val read = mutableListOf<String>()

        index.scan(index.staleAmong(stamps), stamps, Dispatchers.Unconfined, read = { paths ->
            read += paths
            paths.associateWith { "* Note\n" }
        })

        assertEquals(listOf("small.org"), read)
        assertEquals(LeftOut.TooLarge, index.facts.getValue("huge.org").leftOut)
        assertNull(index.facts.getValue("small.org").leftOut)
    }

    /** Running out of memory on one file must not cost the app, nor the other notes their facts. */
    @Test
    fun aNoteThatCannotBeReadIsLeftOutAndTheOthersGoOn() = runTest {
        val index = NoteIndex(budgets = roomy)
        val stamps = (1..20).associate { "n$it.org" to FileStamp(1, 10) }

        index.scan(index.staleAmong(stamps), stamps, Dispatchers.Unconfined, read = { paths ->
            if ("n7.org" in paths) throw Error("Failed to allocate")
            paths.associateWith { "* $it\n" }
        })

        assertEquals(LeftOut.Unreadable, index.facts.getValue("n7.org").leftOut)
        assertEquals(19, index.facts.values.count { it.leftOut == null })
    }

    /** Running out of memory says nothing about the next run, which may have more of it. */
    @Test
    fun aNoteThatCouldNotBeReadIsNotKeptAsIfItWereKnown() = runTest {
        val file = MemoryIndexFile()
        val index = NoteIndex(file, roomy)
        val stamps = mapOf("a.org" to FileStamp(1, 10), "b.org" to FileStamp(1, 10))

        index.scan(index.staleAmong(stamps), stamps, Dispatchers.Unconfined, read = { paths ->
            if ("a.org" in paths) throw Error("Failed to allocate")
            paths.associateWith { "* $it\n" }
        })

        assertTrue(index.staleAmong(stamps).isEmpty(), "not tried again in this run")
        val again = NoteIndex(file, roomy)
        again.load()
        assertEquals(listOf("a.org"), again.staleAmong(stamps), "but in the next")
    }

    /** A note with thousands of `[[…]]`, as in a generated log, must not be gone through for each. */
    @Test
    fun manyPlainLinksInANoteAreSettledInOnePass() {
        val text = "* Heading\n" + (1..3000).joinToString("\n") { "see [[Heading]] and [[nothing $it]]" } + "\n"
        val mark = kotlin.time.TimeSource.Monotonic.markNow()

        val facts = factsOf("log.org", text)

        assertEquals(3000, facts.links.size)
        assertTrue(mark.elapsedNow() < kotlin.time.Duration.parse("20s"), "took ${mark.elapsedNow()}")
    }

    /** What runs with the app closed, finding the folder gone: it must not throw away what is known. */
    @Test
    fun aRefreshThatCannotReachTheNotesKeepsWhatIsKnown() = runTest {
        val file = MemoryIndexFile()
        NoteIndex(file, roomy).refresh(DocumentStore(InMemoryReplicaStorage(notes)), Dispatchers.Unconfined)
        val before = file.parts.toMap()
        val outOfReach = object : ReplicaStorage by InMemoryReplicaStorage() {
            override suspend fun stamps(): Map<String, FileStamp>? = null
        }

        val index = NoteIndex(file, roomy)
        index.refresh(DocumentStore(outOfReach), Dispatchers.Unconfined)

        assertEquals(notes.keys, index.facts.keys)
        assertEquals(before, file.parts.toMap())
    }

    /** A note a read comes back without is unreachable for now, and is tried again the next time. */
    @Test
    fun aNoteThatCouldNotBeReachedIsTriedAgain() = runTest {
        val index = NoteIndex(budgets = roomy)
        val stamps = mapOf("a.org" to FileStamp(1, 10))

        index.scan(index.staleAmong(stamps), stamps, Dispatchers.Unconfined, read = { emptyMap() })

        assertEquals(listOf("a.org"), index.staleAmong(stamps))
    }

    /** However many notes there are: the last line of defence. */
    @Test
    fun factsPastTheirBudgetAreLeftOutAndSoSaid() = runTest {
        val index = NoteIndex(budgets = roomy.copy(facts = 4_000))
        val stamps = (1..100).associate { "n${it + 100}.org" to FileStamp(1, 10) }

        index.scan(index.staleAmong(stamps), stamps, Dispatchers.Unconfined, read = { paths ->
            paths.associateWith { "* Heading of $it\n<2026-11-05 Thu>\n" }
        })

        assertTrue(index.noRoomFor in 1..99, "some fit and some did not: ${index.noRoomFor}")
        assertTrue(index.facts.values.sumOf { it.weight } <= 4_000 + 100 * NoteFacts(leftOut = LeftOut.NoRoom).weight)
    }

    @Test
    fun theSmallestNotesComeFirstButKnownOnesBeforeThem() = runTest {
        val index = NoteIndex(budgets = roomy)
        index.record("known-and-huge.org", FileStamp(1, 1000), NoteFacts())
        val stamps = mapOf(
            "known-and-huge.org" to FileStamp(2, 1000),
            "new-big.org" to FileStamp(1, 500),
            "new-small.org" to FileStamp(1, 5),
        )

        assertEquals(listOf("known-and-huge.org", "new-small.org", "new-big.org"), index.staleAmong(stamps))
    }

    @Test
    fun aCacheLetsGoOfWhatWasUsedLongestAgo() {
        val cache = SizedCache<String>(10) { it.length.toLong() }
        cache["a"] = "aaaa"
        cache["b"] = "bbbb"
        assertEquals("aaaa", cache["a"], "a is now the more recently used")
        cache["c"] = "cccc"
        assertTrue("a" in cache)
        assertFalse("b" in cache)
        assertTrue("c" in cache)
    }

    @Test
    fun aCacheDoesNotHoldWhatCouldNeverFit() {
        val cache = SizedCache<String>(3) { it.length.toLong() }
        cache["a"] = "aaa"
        cache["b"] = "bbbb"
        assertFalse("b" in cache)
        assertTrue("a" in cache, "nothing was pushed out for it")
        cache["a"] = "toolong"
        assertFalse("a" in cache, "the older value is not left behind as if it were current")
    }

    @Test
    fun whatIsPinnedStaysHoweverBig() {
        val cache = SizedCache<String>(5) { it.length.toLong() }
        cache["other"] = "xx"
        cache.pinned = "open"
        cache["open"] = "much too big to fit"
        assertEquals("much too big to fit", cache.peek("open"))
        assertFalse("other" in cache, "it pushed the rest out")
        cache.pinned = null
        assertFalse("open" in cache, "and goes once it is not on screen")
    }
}
