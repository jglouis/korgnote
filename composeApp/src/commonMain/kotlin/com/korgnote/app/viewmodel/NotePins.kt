package com.korgnote.app.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Notes pinned for quick access: drawn above the file tree, and the first of them opened at launch.
 *
 * Which notes they are is the reader's choice, kept in settings rather than in the notes, so pinning
 * changes nothing on disk and nothing syncs.
 */
class NotePins(
    private val library: NoteLibrary,
    load: () -> List<String>,
    private val save: (List<String>) -> Unit,
) {
    private var pinned by mutableStateOf(load())

    /**
     * The pinned notes there are, in the order they were pinned.
     *
     * A pin whose note is missing is hidden rather than dropped: the replica may not have caught up
     * yet, and a note renamed or deleted on another device can come back.
     */
    val shown: List<String> get() = library.paths.let { paths -> pinned.filter { it in paths } }

    fun isPinned(path: String): Boolean = path in pinned

    /** Pins [path] at the end of the list, or unpins it. */
    fun toggle(path: String) {
        update(if (path in pinned) pinned - path else pinned + path)
    }

    /** Keeps [from]'s pin, in its place, for a note renamed to [to]. */
    internal fun move(from: String, to: String) {
        if (from in pinned) update(pinned.map { if (it == from) to else it }.distinct())
    }

    internal fun forget(path: String) {
        if (path in pinned) update(pinned - path)
    }

    private fun update(paths: List<String>) {
        pinned = paths
        save(paths)
    }
}

/**
 * What each of [paths] is called in the pinned list: its file name, or its whole path where two
 * pinned notes share a name and the name alone would not say which is which.
 */
fun pinLabels(paths: List<String>): List<String> {
    val names = paths.map { it.substringAfterLast('/') }
    val repeated = names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    return paths.mapIndexed { i, path -> if (names[i] in repeated) path else names[i] }
}
