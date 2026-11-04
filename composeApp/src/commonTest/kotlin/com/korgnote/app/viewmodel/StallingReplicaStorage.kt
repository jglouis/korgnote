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

    private var readsToStall = mutableMapOf<String?, CompletableDeferred<Unit>>()
    private var writesToStall = mutableMapOf<String?, CompletableDeferred<Unit>>()

    /** The next read of [path], or of any note when null, waits until the result is completed. */
    fun stallNextRead(path: String? = null): CompletableDeferred<Unit> =
        CompletableDeferred<Unit>().also { readsToStall[path] = it }

    /** The next write of [path], or of any note when null, waits until the result is completed. */
    fun stallNextWrite(path: String? = null): CompletableDeferred<Unit> =
        CompletableDeferred<Unit>().also { writesToStall[path] = it }

    override suspend fun read(path: String): String? {
        val text = files.read(path)
        (readsToStall.remove(path) ?: readsToStall.remove(null))?.await()
        return text
    }

    override suspend fun write(path: String, content: String) {
        (writesToStall.remove(path) ?: writesToStall.remove(null))?.await()
        files.write(path, content)
    }
}
