package com.korgnote.app.viewmodel

import com.korgnote.app.data.ReadingPlace
import com.korgnote.common.org.orgElements
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * Where each recently read note was left, so that opening it again — from the drawer, a pin, or
 * the app starting — goes back there rather than to its top. The most recently read comes first,
 * and is the note the app opens on.
 *
 * Kept in settings, per device, like the pins: where a phone was left in a note is not where a
 * desktop was. Only the [MAX_PLACES] most recent are kept.
 */
class ReadingPlaces(
    load: () -> List<ReadingPlace>,
    private val save: (List<ReadingPlace>) -> Unit,
    private val note: OpenNote,
    private val scope: CoroutineScope,
) {
    private var places: List<ReadingPlace> = load()

    /** Where the reader is in the open note, by element, not yet kept. */
    private var unsavedPlace: Pair<String, ReadingSpot>? = null
    private var placeSave: Job? = null

    /**
     * The reading view of [path] now has [elementIndex] at its top, scrolled [pixels] into it: to
     * be kept as where the note was left, once the scrolling stops. Ignored while the view still
     * shows where it was before this note was opened, and for a note no longer open.
     *
     * Called on every frame of a scroll, so it only notes the spot: working out where it is in the
     * text, and storing it, wait for [saveReadingPlace].
     */
    fun onReadingPlaceChanged(path: String, elementIndex: Int, pixels: Int) {
        if (path != note.path || !note.settled) return
        unsavedPlace = path to ReadingSpot(elementIndex, pixels)
        placeSave?.cancel()
        placeSave = scope.launch {
            delay(PLACE_SAVE_PAUSE)
            saveReadingPlace()
        }
    }

    /**
     * Keeps where the reader is in the open note now, rather than once scrolling stops: before
     * another note replaces it, or when the app goes to the background and may not come back.
     */
    fun saveReadingPlace() {
        placeSave?.cancel()
        placeSave = null
        val (path, spot) = unsavedPlace ?: return
        unsavedPlace = null
        // The element is one of the open note's: of any other, its index means nothing.
        if (path != note.path) return
        val elements = note.content.orgElements()
        // Past the last element is the backlinks below it: the last element is near enough.
        val element = elements.getOrNull(spot.elementIndex) ?: elements.lastOrNull() ?: return
        record(path, element.start, if (spot.elementIndex in elements.indices) spot.pixels else 0)
    }

    /** Where [path] was left, or null when it has not been read here lately. */
    fun placeOf(path: String): ReadingPlace? = places.firstOrNull { it.path == path }

    /** The most recently read note for which [exists] holds, to open the app on. */
    fun lastRead(exists: (String) -> Boolean): String? = places.firstOrNull { exists(it.path) }?.path

    /** [path] was opened: it is now the most recently read, still where it was left. */
    internal fun opened(path: String) {
        update(placeOf(path) ?: ReadingPlace(path, offset = 0))
    }

    /** The reader is now at [offset], [pixels] into the element starting there, in [path]. */
    internal fun record(path: String, offset: Int, pixels: Int) {
        update(ReadingPlace(path, offset, pixels))
    }

    /** Keeps [from]'s place for a note renamed to [to]. */
    internal fun move(from: String, to: String) {
        if (places.none { it.path == from }) return
        store(places.filter { it.path != to }.map { if (it.path == from) it.copy(path = to) else it })
    }

    internal fun forget(path: String) {
        if (places.any { it.path == path }) store(places.filter { it.path != path })
    }

    private fun update(place: ReadingPlace) {
        if (places.firstOrNull() == place) return
        store((listOf(place) + places.filter { it.path != place.path }).take(MAX_PLACES))
    }

    private fun store(updated: List<ReadingPlace>) {
        places = updated
        save(updated)
    }

    companion object {
        /** Enough for every note read in a good while, and small enough to store in one setting. */
        const val MAX_PLACES = 50

        /** How long scrolling has to stop before where the reader stopped is kept. */
        internal val PLACE_SAVE_PAUSE = 500.milliseconds
    }
}
