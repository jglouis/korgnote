package com.korgnote.app.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.korgnote.common.replica.FolderNoteStorage
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.vm_folder_conflict
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Keeping the screen in step with a folder of notes that other apps write to.
 *
 * Neither Android nor iOS says when a file in such a folder changes, so the folder is looked at
 * again when the app comes to the front, and every [every] while it stays there; a look costs a
 * listing of the folder, and only a note whose file moved is read again. Nothing is followed in the
 * background, where nobody is looking.
 *
 * A save is written to the folder at once, there being nobody else to send it to: what is left to
 * do after it is saying so when it went to a conflict copy, or could not be written at all.
 */
class FolderSync(
    private val scope: CoroutineScope,
    private val storage: FolderNoteStorage,
    /** Shows whatever the folder now holds; run after anything that may have changed it. */
    private val onReplicaChanged: suspend () -> Unit,
    /** Shows a message to the user; an empty one clears it. */
    private val showMessage: (String) -> Unit,
    private val now: () -> Instant = { Clock.System.now() },
    private val every: Duration = 10.seconds,
) : NotesSync {

    override var unreachable by mutableStateOf<String?>(null)
        private set

    override var retrying by mutableStateOf(false)
        private set

    override var lastSynced by mutableStateOf<Instant?>(null)
        private set

    private var inFlight by mutableIntStateOf(0)

    override val syncing: Boolean get() = inFlight > 0

    /** The looks taken while the app is in front; null while it is not. */
    private var looking: Job? = null

    /**
     * When the last look began, so that two do not run back to back: a look costs a second on some
     * phones. Set from the start, since the first [load] looks: the app comes to the front at the
     * same moment, and both would otherwise read every note.
     */
    private var lastLook: TimeMark? = TimeSource.Monotonic.markNow()

    private var stopped = false

    override suspend fun load() {
        look()
        onReplicaChanged()
    }

    override suspend fun retry() {
        if (retrying) return
        retrying = true
        try {
            load()
        } finally {
            retrying = false
        }
    }

    /** Following starts once the app is in front: see [onResume]. */
    override fun watch() {
        stopped = false
    }

    override fun stop() {
        stopped = true
        onPause()
    }

    override fun onResume() {
        if (stopped || looking != null) return
        looking = scope.launch {
            // Changes made while the app was away first, then every so often while it is here.
            // At start the first load has just looked, or is looking: this waits its turn.
            while (isActive) {
                lastLook?.let { delay(every - it.elapsedNow()) }
                if (look()) onReplicaChanged()
            }
        }
    }

    override fun onPause() {
        looking?.cancel()
        looking = null
    }

    override suspend fun push(writeFirst: suspend () -> Unit) {
        writeFirst()
        afterWrite()
    }

    override suspend fun pushQuietly() = afterWrite()

    /**
     * Says what became of the saves just made: a conflict copy is a new note to show, and one the
     * folder refused is kept until [look] gets it written.
     */
    private suspend fun afterWrite() {
        val conflicted = storage.takeConflicts()
        unreachable = storage.problem
        if (conflicted.isEmpty()) return
        showMessage(getString(Res.string.vm_folder_conflict, conflicted.joinToString(", ")))
        onReplicaChanged()
    }

    /** Lists the folder again. True when it changed. */
    private suspend fun look(): Boolean {
        lastLook = TimeSource.Monotonic.markNow()
        inFlight++
        try {
            val changed = storage.rescan()
            unreachable = storage.problem
            if (unreachable == null) lastSynced = now()
            return changed
        } finally {
            inFlight--
        }
    }
}
