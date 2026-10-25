package com.korgnote.app.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.korgnote.common.replica.SyncEngine
import com.korgnote.common.replica.SyncReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

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
) {
    /**
     * Why the server could not be reached on the last attempt, or null when it answered.
     *
     * Kept out of the message line on purpose: being offline is an ordinary state for this app, not
     * an error about what the user just did, so it gets a quiet indicator rather than a sentence on
     * top of the note.
     */
    var unreachable by mutableStateOf<String?>(null)
        private set

    /** True while a [retry] the user asked for is in flight. */
    var retrying by mutableStateOf(false)
        private set

    /** Cuts the change stream's backoff short, so a retry that got through reconnects it at once. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /**
     * Runs a sync cycle when the server can be reached, then shows whatever the replica holds.
     *
     * A network failure is not fatal: the replica *is* the content, so being offline costs
     * freshness, not access. It sets [unreachable] instead of a message.
     */
    suspend fun load() {
        try {
            val report = sync.sync()
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
    suspend fun retry() {
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

    /**
     * Follows the server's change stream for as long as the app runs, so an edit made in Emacs shows
     * up without a refresh.
     *
     * Reconnects with a backoff, and re-syncs after each reconnection: the stream is a hint, so a
     * gap in it costs latency, never an edit.
     */
    fun watch() {
        if (watching) return
        watching = true
        scope.launch {
            var backoff = 1.seconds
            while (isActive) {
                try {
                    sync.watch { onReplicaChanged() }
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
                // Not runCatching: it would swallow cancellation and keep the loop alive after the
                // view model is gone.
                try {
                    reportConflicts(sync.sync())
                    unreachable = null
                    onReplicaChanged()
                } catch (catchUpFailed: Throwable) {
                    // Cancellation must still cancel; anything else just means still offline, and
                    // the next reconnection will try again.
                    currentCoroutineContext().ensureActive()
                    unreachable = reason(catchUpFailed)
                    println("[DEBUG_LOG][ServerSync] Catch-up sync failed: ${catchUpFailed.message}")
                }
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
    suspend fun push(writeFirst: suspend () -> Unit = {}) {
        try {
            writeFirst()
            val report = sync.sync()
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
            t.printStackTrace()
        }
    }

    /**
     * Pushes what just changed, if the server can be reached; offline, it waits for the next sync.
     * No message either way: a created, renamed or deleted note needs none to say it was sent.
     */
    suspend fun pushQuietly() {
        try {
            reportConflicts(sync.sync())
            unreachable = null
            onReplicaChanged()
        } catch (t: Throwable) {
            currentCoroutineContext().ensureActive()
            unreachable = reason(t)
            println("[DEBUG_LOG][ServerSync] Sync after a file change failed: ${t.message}")
        }
    }

    /**
     * Says which notes just took the server's version, since the text on screen changes under the
     * reader. True when there was something to say.
     */
    private fun reportConflicts(report: SyncReport): Boolean {
        if (report.conflicted.isEmpty()) return false
        val names = report.conflicted.joinToString(", ")
        showMessage(
            "$names also changed on the server. The server's version is kept; yours is " +
                "saved beside it as a .sync-conflict- copy."
        )
        return true
    }

    private fun reason(t: Throwable): String {
        val msg = t.message ?: ""
        val simple = t::class.simpleName ?: "Error"
        return "Cannot reach the server. Please check Settings and your connection. ($simple: $msg)"
    }
}
