package com.korgnote.app.viewmodel

import com.korgnote.app.data.ReadingPlace

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
) {
    private var places: List<ReadingPlace> = load()

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
    }
}
