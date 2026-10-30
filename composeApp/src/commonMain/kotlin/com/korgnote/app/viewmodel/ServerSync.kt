package com.korgnote.app.viewmodel

import androidx.compose.runtime.getValue
import com.korgnote.app.data.NotPairedException
import com.korgnote.app.data.WebDavException
import com.korgnote.app.data.WebDavRefusedException
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.korgnote.common.replica.SyncEngine
import com.korgnote.common.replica.SyncReport
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.vm_server_conflict
import korgnote.composeapp.generated.resources.vm_server_unreachable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.getString
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Talking to the server: sync cycles, and following its change stream.
 *
 * The replica is the content, so none of this is ever in the way of reading or editing: a failure
 * here costs freshness, never access, and never an edit.
 */
class ServerSync(
    private val scope: CoroutineScope,
    private val sync: SyncEngine,
    /** Shows whatever the replica now holds; run after anything that may have changed it. */
    private val onReplicaChanged: suspend () -> Unit,
    /** Shows a message to the user; an empty one clears it. */
    private val showMessage: (String) -> Unit,
    private val now: () -> Instant = { Clock.System.now() },
    /** Takes an edit that could not be sent to the server once the network is back, the app open or not. */
    private val background: BackgroundSync = NoBackgroundSync,
    /**
     * What [unreachable] says for a failure: by default in the app's language, read from its
     * resources on a thread of their own, which a test on virtual time can pass a plain one for.
     */
    private val reason: suspend (Throwable) -> String = ::unreachableReason,
) : NotesSync {
    /**
     * Why the server could not be reached on the last attempt, or null when it answered.
     *
     * Kept out of the message line on purpose: being offline is an ordinary state for this app, not
     * an error about what the user just did, so it gets a quiet indicator rather than a sentence on
     * top of the note.
     */
    override var unreachable by mutableStateOf<String?>(null)
        private set

    /** True while a [retry] the user asked for is in flight. */
    override var retrying by mutableStateOf(false)
        private set

    /**
     * When the replica last matched the server: the end of the last sync that got through, or the
     * last change the stream delivered. Null until the first one, this run.
     */
    override var lastSynced by mutableStateOf<Instant?>(null)
        private set

    /** Syncs in flight, of any kind: a save's push as well as a retry or the stream's catch-up. */
    private var inFlight by mutableIntStateOf(0)

    /** True while any sync is talking to the server, for the status icon to show it working. */
    override val syncing: Boolean get() = inFlight > 0

    /** Cuts the change stream's backoff short, so a retry that got through reconnects it at once. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /**
     * Runs a sync cycle when the server can be reached, then shows whatever the replica holds.
     *
     * A network failure is not fatal: the replica *is* the content, so being offline costs
     * freshness, not access. It sets [unreachable] instead of a message.
     */
    override suspend fun load() {
        try {
            val report = syncOnce()
            unreachable = null
            if (!reportConflicts(report)) showMessage("")
        } catch (t: Throwable) {
            // Rethrows if this job was cancelled, so cancellation stays cancellation instead of
            // being reported as a failure.
            currentCoroutineContext().ensureActive()
            unreachable = reason(t)
        }
        onReplicaChanged()
    }

    /**
     * Syncs now, because the user asked to; on success the change stream reconnects at once
     * instead of waiting out its backoff.
     */
    override suspend fun retry() {
        if (retrying) return
        retrying = true
        try {
            load()
        } finally {
            retrying = false
        }
        if (unreachable == null) wake.trySend(Unit)
    }

    /** Guards against starting a second event stream on recomposition. */
    private var watching = false

    /** The change stream being followed, for [stop] to end. */
    private var watchJob: Job? = null

    /**
     * Ends the change stream. A sync already under way is left to finish: it only ever writes the
     * replica, never the folder the notes may now be kept in instead.
     */
    override fun stop() {
        watchJob?.cancel()
        watchJob = null
        watching = false
    }

    /**
     * Follows the server's change stream for as long as the app runs, so an edit made in Emacs shows
     * up without a refresh.
     *
     * Reconnects with a backoff, and re-syncs after each reconnection: the stream is a hint, so a
     * gap in it costs latency, never an edit.
     */
    override fun watch() {
        if (watching) return
        watching = true
        watchJob = scope.launch {
            var backoff = 1.seconds
            while (isActive) {
                try {
                    sync.watch {
                        lastSynced = now()
                        onReplicaChanged()
                    }
                    // A clean end means the server closed the stream; reconnect promptly.
                    backoff = 1.seconds
                } catch (t: Throwable) {
                    // Cancellation is the app shutting down, not a dropped connection.
                    currentCoroutineContext().ensureActive()
                    println("[DEBUG_LOG][ServerSync] Event stream dropped: ${t.message}")
                    // Back off so an unreachable server is not hammered, but keep trying: without
                    // this the app silently stops seeing edits made elsewhere.
                    backoff = (backoff * 2).coerceAtMost(30.seconds)
                }
                // A retry that reached the server wakes this early, and the backoff starts over.
                if (withTimeoutOrNull(backoff) { wake.receive() } != null) backoff = 1.seconds
                // Catch up on whatever was missed while disconnected; the stream is only a hint.
                // Offline, the next reconnection tries again.
                catchUp("Catch-up sync")
            }
        }
    }

    /**
     * Pushes an edit that is already saved locally, marking the server [unreachable] when it
     * cannot be reached.
     *
     * The local write cannot fail on a network problem, so an edit is never lost to one. If the
     * push fails the file simply stays dirty and the next sync carries it. [writeFirst] runs under
     * the same guard, for a caller that makes its local write in this coroutine.
     */
    override suspend fun push(writeFirst: suspend () -> Unit) {
        try {
            writeFirst()
            val report = syncOnce()
            unreachable = null
            // A merge or a conflict changed the note under the screen. Left showing the old text,
            // the next save would write it back over what the server added.
            if (report.replicaMoved) {
                reportConflicts(report)
                onReplicaChanged()
            }
        } catch (t: Throwable) {
            // Rethrows if this job was cancelled, so cancellation stays cancellation
            // instead of being reported as a failure.
            currentCoroutineContext().ensureActive()
            unreachable = reason(t)
            background.whenOnline()
            t.printStackTrace()
        }
    }

    /**
     * Pushes what just changed, if the server can be reached; offline, it waits for the next sync.
     * No message either way: a created, renamed or deleted note needs none to say it was sent.
     */
    override suspend fun pushQuietly() = catchUp("Sync after a file change", onFailure = background::whenOnline)

    /**
     * Catches up with changes made while the app was away. The stream may have been cut meanwhile
     * without either end knowing, and the app has no other way to hear of them; and if it was cut,
     * waking it saves waiting out its backoff.
     *
     * Not at start, which the first [load] covers before [watch] begins, nor after [stop].
     */
    override fun onResume() {
        if (!watching) return
        scope.launch { catchUp("Sync on returning to the app") }
        wake.trySend(Unit)
    }

    /**
     * One sync, with no message either way, then shows what the replica holds; marks the server
     * [unreachable] when it cannot be reached.
     *
     * Not runCatching: it would swallow cancellation, and keep a loop alive after the view model is
     * gone.
     */
    private suspend fun catchUp(what: String, onFailure: () -> Unit = {}) {
        try {
            reportConflicts(syncOnce())
            unreachable = null
            onReplicaChanged()
        } catch (t: Throwable) {
            currentCoroutineContext().ensureActive()
            unreachable = reason(t)
            onFailure()
            println("[DEBUG_LOG][ServerSync] $what failed: ${t.message}")
        }
    }

    /** One sync cycle, counted in [syncing] while it runs and stamped in [lastSynced] when it gets through. */
    private suspend fun syncOnce(): SyncReport {
        inFlight++
        try {
            return sync.sync().also { lastSynced = now() }
        } finally {
            inFlight--
        }
    }

    /**
     * Says which notes just took the server's version, since the text on screen changes under the
     * reader. True when there was something to say.
     */
    private suspend fun reportConflicts(report: SyncReport): Boolean {
        if (report.conflicted.isEmpty()) return false
        showMessage(getString(Res.string.vm_server_conflict, report.conflicted.joinToString(", ")))
        return true
    }

}

/** Why the server could not be reached, in the words of the app's language. */
internal suspend fun unreachableReason(t: Throwable): String {
    // Reached, and refused: what to do about it is not checking the connection.
    if (t is NotPairedException || t is WebDavRefusedException || t is WebDavException) return t.message.orEmpty()
    val msg = t.message ?: ""
    val simple = t::class.simpleName ?: "Error"
    return getString(Res.string.vm_server_unreachable, simple, msg)
}
