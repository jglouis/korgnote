package com.korgnote.app.viewmodel

import com.korgnote.common.replica.InMemoryReplicaStorage
import com.korgnote.common.replica.ReplicaStorage
import kotlinx.coroutines.CompletableDeferred

/**
 * A replica whose disk can be made slow at a chosen moment, so a test can land something else in the
 * window a real read or write leaves open. A stalled read has already read what it returns; a stalled
 * write has not written anything yet.
 */
class StallingReplicaStorage(
    private val files: ReplicaStorage = InMemoryReplicaStorage(),
) : ReplicaStorage by files {

    private class Stall(var skipping: Int, val gate: CompletableDeferred<Unit> = CompletableDeferred())

    private val reads = mutableMapOf<String, MutableList<Stall>>()
    private val writes = mutableMapOf<String, MutableList<Stall>>()

    /** Every note read, in order, for a test to count. */
    val read = mutableListOf<String>()

    /** The read of [path] after the next [skipping] waits until the result is completed. */
    fun stallRead(path: String, skipping: Int = 0): CompletableDeferred<Unit> =
        Stall(skipping).also { reads.getOrPut(path) { mutableListOf() } += it }.gate

    /** The next write of [path] waits until the result is completed. */
    fun stallWrite(path: String): CompletableDeferred<Unit> =
        Stall(0).also { writes.getOrPut(path) { mutableListOf() } += it }.gate

    override suspend fun read(path: String): String? {
        read += path
        val text = files.read(path)
        reads[path].awaitTurn()
        return text
    }

    override suspend fun write(path: String, content: String) {
        writes[path].awaitTurn()
        files.write(path, content)
    }

    /** Each stall counts this access down; the first with none left to skip is waited on. */
    private suspend fun MutableList<Stall>?.awaitTurn() {
        if (this == null) return
        val due = firstOrNull { it.skipping == 0 }
        forEach { if (it !== due) it.skipping-- }
        if (due != null) {
            remove(due)
            due.gate.await()
        }
    }
}
