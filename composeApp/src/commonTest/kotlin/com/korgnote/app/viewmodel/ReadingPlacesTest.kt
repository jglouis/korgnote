package com.korgnote.app.viewmodel

import com.korgnote.app.data.ReadingPlace
import com.korgnote.app.data.decodeReadingPlaces
import com.korgnote.app.data.encodeReadingPlaces
import com.korgnote.common.org.orgElements
import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.InMemoryReplicaStorage
import com.korgnote.common.replica.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.*

/**
 * Where each note was left: kept as the reader scrolls, gone back to when the note opens again
 * with no place asked for, and the note last read is the one the app opens on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadingPlacesTest {

    private val long = "long.org"
    private val other = "other.org"
    private val longText = "* One\nbody\n* Two\nbody\n* Three\nbody\n* Four\nbody\n"

    private lateinit var server: FakeSyncApi
    private lateinit var store: DocumentStore
    /** What settings hold, as the view model last saved it. */
    private var saved = emptyList<ReadingPlace>()
    private var pinned = emptyList<String>()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = FakeSyncApi(mutableMapOf(long to longText, other to "* Other\n", "a.org" to "* A\n"))
        store = DocumentStore(InMemoryReplicaStorage())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = NotesViewModel(
        SyncEngine(store, server),
        store,
        pinnedNotes = { pinned },
        readingPlaces = { saved },
        saveReadingPlaces = { saved = it },
    )

    private fun startOf(index: Int) = longText.orgElements()[index].start

    /** What the reading view does once a note's text is on screen: it goes where it was told. */
    private fun NotesViewModel.viewSettles() {
        if (note.scrollToElementIndex != null) note.onScrolledToElement() else note.onRestored()
    }

    @Test
    fun aNoteOpensAgainWhereItWasLeft() = runTest {
        val vm = viewModel()
        vm.server.load()
        vm.onFileSelected(long)
        vm.viewSettles()

        vm.places.onReadingPlaceChanged(long, 2, 40)
        vm.onFileSelected(other)
        vm.viewSettles()
        vm.onFileSelected(long)

        assertEquals(ReadingSpot(2, 40), vm.note.restoreTo)
        assertNull(vm.note.scrollToElementIndex, "nothing was jumped to, so nothing is lit")
        assertEquals(ReadingPlace(long, startOf(2), 40), saved.first())
    }

    @Test
    fun aNoteNeverReadHereOpensAtItsTop() = runTest {
        val vm = viewModel()
        vm.server.load()
        vm.viewSettles()
        vm.places.onReadingPlaceChanged(vm.note.path!!, 0, 12)

        vm.onFileSelected(long)

        assertEquals(ReadingSpot(0, 0), vm.note.restoreTo, "not wherever the last note was")
    }

    @Test
    fun theNoteLastReadOpensAtLaunchWhereItWasLeftRatherThanThePinnedOne() = runTest {
        pinned = listOf(other)
        saved = listOf(ReadingPlace(long, startOf(1), 15), ReadingPlace(other, 0))

        val vm = viewModel()
        vm.server.load()

        assertEquals(long, vm.note.path)
        assertEquals(ReadingSpot(1, 15), vm.note.restoreTo)
    }

    @Test
    fun aNoteLastReadButGoneSinceGivesWayToTheNextOne() = runTest {
        saved = listOf(ReadingPlace("deleted.org", 0), ReadingPlace(other, 0))

        val vm = viewModel()
        vm.server.load()

        assertEquals(other, vm.note.path)
    }

    @Test
    fun aJumpGoesWhereItWasAskedNotWhereTheNoteWasLeft() = runTest {
        saved = listOf(ReadingPlace(long, startOf(1), 15))
        val vm = viewModel()
        vm.server.load()
        vm.onFileSelected(other)

        vm.onFileSelected(long, scrollToOffset = startOf(3))

        assertEquals(3, vm.note.scrollToElementIndex)
        assertNull(vm.note.restoreTo)
    }

    @Test
    fun whereTheViewWasBeforeTheNoteSettledIsNoPlaceInIt() = runTest {
        saved = listOf(ReadingPlace(long, startOf(1), 15))
        val vm = viewModel()
        vm.server.load()

        // Still showing the note opened before, three elements down.
        vm.places.onReadingPlaceChanged(long, 3, 0)
        vm.places.saveReadingPlace()

        assertEquals(ReadingPlace(long, startOf(1), 15), saved.first())
    }

    @Test
    fun thePlaceIsKeptOnceScrollingStops() = runTest {
        val vm = viewModel()
        vm.server.load()
        vm.onFileSelected(long)
        vm.viewSettles()

        vm.places.onReadingPlaceChanged(long, 1, 0)
        vm.places.onReadingPlaceChanged(long, 3, 7)
        assertEquals(ReadingPlace(long, 0), saved.first(), "not on every frame of the scroll")

        advanceTimeBy(ReadingPlaces.PLACE_SAVE_PAUSE.inWholeMilliseconds + 1)
        assertEquals(ReadingPlace(long, startOf(3), 7), saved.first())
    }

    @Test
    fun aRenamedNoteKeepsItsPlaceAndADeletedOneLosesIt() = runTest {
        saved = listOf(ReadingPlace(long, startOf(2), 5))
        val vm = viewModel()
        vm.server.load()

        assertIs<NoteFileResult.Done>(vm.noteFiles.renameNote(long, "renamed.org"))
        assertEquals(ReadingSpot(2, 5), vm.note.restoreTo)
        assertEquals(ReadingPlace("renamed.org", startOf(2), 5), saved.first())

        vm.noteFiles.deleteNote("renamed.org")
        assertTrue(saved.none { it.path == "renamed.org" })
    }

    @Test
    fun onlyTheMostRecentPlacesAreKept() {
        var kept = emptyList<ReadingPlace>()
        val places = ReadingPlaces({ kept }, { kept = it }, OpenNote(), CoroutineScope(Dispatchers.Unconfined))

        repeat(ReadingPlaces.MAX_PLACES + 5) { places.record("note$it.org", it, 0) }

        assertEquals(ReadingPlaces.MAX_PLACES, kept.size)
        assertEquals("note${ReadingPlaces.MAX_PLACES + 4}.org", kept.first().path)
        assertNull(places.placeOf("note0.org"))
    }

    @Test
    fun placesAreStoredAsOneStringAndAnUnreadableOneIsNone() {
        val places = listOf(ReadingPlace("a/b.org", 120, 33), ReadingPlace("c.org", 0))

        assertEquals(places, decodeReadingPlaces(encodeReadingPlaces(places)))
        assertEquals(emptyList(), decodeReadingPlaces("not json"))
        assertEquals(emptyList(), decodeReadingPlaces(null))
    }
}
