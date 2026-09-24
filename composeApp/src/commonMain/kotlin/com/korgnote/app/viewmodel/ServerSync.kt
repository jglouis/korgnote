package com.korgnote.app.viewmodel

import com.korgnote.common.replica.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
     * Runs a sync cycle when the server can be reached, then shows whatever the replica holds.
     *
     * A network failure is not fatal: the replica *is* the content, so being offline costs
     * freshness, not access.
     */
    suspend fun load() {
        try {
            val report = sync.sync()
            if (report.conflicted.isNotEmpty()) {
                val names = report.conflicted.joinToString(", ")
                showMessage(
                    "Kept your version of $names. The other version is saved beside " +
                        "it as a .sync-conflict- copy."
                )
            } else {
                showMessage("")
            }
        } catch (t: Throwable) {
            // Rethrows if this job was cancelled, so cancellation stays cancellation instead of
            // being reported as a failure.
            currentCoroutineContext().ensureActive()
            showMessage(friendlyErrorMessage(t))
        }
        onReplicaChanged()
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
                delay(backoff)
                // Catch up on whatever was missed while disconnected; the stream is only a hint.
                // Not runCatching: it would swallow cancellation and keep the loop alive after the
                // view model is gone.
                try {
                    sync.sync()
                    onReplicaChanged()
                } catch (catchUpFailed: Throwable) {
                    // Cancellation must still cancel; anything else just means still offline, and
                    // the next reconnection will try again.
                    currentCoroutineContext().ensureActive()
                    println("[DEBUG_LOG][ServerSync] Catch-up sync failed: ${catchUpFailed.message}")
                }
            }
        }
    }

    /**
     * Pushes an edit that is already saved locally, saying so when the server cannot be reached.
     *
     * The local write cannot fail on a network problem, so an edit is never lost to one. If the
     * push fails the file simply stays dirty and the next sync carries it. [writeFirst] runs under
     * the same guard, for a caller that makes its local write in this coroutine.
     */
    suspend fun push(writeFirst: suspend () -> Unit = {}) {
        try {
            writeFirst()
            sync.sync()
            showMessage("")
        } catch (t: Throwable) {
            // Rethrows if this job was cancelled, so cancellation stays cancellation
            // instead of being reported as a failure.
            currentCoroutineContext().ensureActive()
            showMessage("Saved locally; could not reach the server: ${t.message}")
            t.printStackTrace()
        }
    }

    /**
     * Pushes what just changed, if the server can be reached; offline, it waits for the next sync.
     * Silent either way: a created, renamed or deleted note needs no message to say it was sent.
     */
    suspend fun pushQuietly() {
        try {
            sync.sync()
            onReplicaChanged()
        } catch (t: Throwable) {
            currentCoroutineContext().ensureActive()
            println("[DEBUG_LOG][ServerSync] Sync after a file change failed: ${t.message}")
        }
    }

    private fun friendlyErrorMessage(t: Throwable): String {
        val msg = t.message ?: ""
        val simple = t::class.simpleName ?: "Error"
        // Generic, user-friendly phrasing
        return "Cannot reach the server. Please check Settings and your connection. ($simple: $msg)"
    }
}
