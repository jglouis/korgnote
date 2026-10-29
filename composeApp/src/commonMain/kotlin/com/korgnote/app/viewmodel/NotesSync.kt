package com.korgnote.app.viewmodel

import kotlin.time.Instant

/**
 * Keeping the notes on screen in step with where they are kept: the server, through the replica, or
 * a folder other apps write to. [NotesViewModel] tells it when the notes changed here, and it calls
 * back when they changed there.
 */
interface NotesSync {
    /** Why the notes could not be reached on the last attempt, or null when they were. */
    val unreachable: String?

    /** True while a [retry] the user asked for is in flight. */
    val retrying: Boolean

    /** True while a sync runs, for the status icon to show it working. */
    val syncing: Boolean

    /** When the notes last matched where they are kept; null until the first time, this run. */
    val lastSynced: Instant?

    /** Catches up with where the notes are kept, then shows whatever they now hold. */
    suspend fun load()

    /** [load], because the user asked to. */
    suspend fun retry()

    /** Follows changes made elsewhere for as long as the app runs, until [stop]. */
    fun watch()

    /** Stops following changes, for good: the notes are now kept somewhere else. */
    fun stop()

    /** The app came to the front: changes made meanwhile may be waiting. */
    fun onResume() {}

    /** The app went to the back: nothing on screen needs following until [onResume]. */
    fun onPause() {}

    /** Passes on an edit already saved here; [writeFirst] makes that save, in this coroutine. */
    suspend fun push(writeFirst: suspend () -> Unit = {})

    /** [push] for a note created, renamed or deleted, which needs no message to say it was sent. */
    suspend fun pushQuietly()
}
