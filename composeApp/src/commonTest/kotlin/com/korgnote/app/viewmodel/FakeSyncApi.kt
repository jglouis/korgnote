package com.korgnote.app.viewmodel

import com.korgnote.common.replica.PushResult
import com.korgnote.common.replica.RemoteEntry
import com.korgnote.common.replica.RemoteFile
import com.korgnote.common.replica.RemoteManifest
import com.korgnote.common.replica.SyncApi

/** A stand-in server for the view-model tests: a map of files with etags derived from content. */
class FakeSyncApi(val files: MutableMap<String, String> = mutableMapOf()) : SyncApi {
    var online = true
    val saves = mutableListOf<Pair<String, String>>()
    private var generation = 1L

    private fun requireOnline() {
        if (!online) throw RuntimeException("Connection refused")
    }

    private fun etagOf(path: String) = "e-$path-${files[path]?.length ?: 0}"

    override suspend fun manifest(since: Long?): RemoteManifest {
        requireOnline()
        return RemoteManifest(
            generation = generation,
            complete = true,
            entries = files.keys.map { RemoteEntry(it, etagOf(it)) },
        )
    }

    override suspend fun fetch(path: String): RemoteFile? {
        requireOnline()
        val text = files[path] ?: return null
        return RemoteFile(path, etagOf(path), text)
    }

    override suspend fun push(path: String, content: String, ifMatch: String?): PushResult {
        requireOnline()
        if (ifMatch != null && files.containsKey(path) && ifMatch != etagOf(path)) {
            return PushResult.Conflict(etagOf(path))
        }
        files[path] = content
        saves.add(path to content)
        generation++
        return PushResult.Pushed(etagOf(path))
    }

    /** Events to deliver, and how many times the stream has been opened. */
    val pending = mutableListOf<Pair<String, Boolean>>()
    var streamOpened = 0
    var dropStream = false

    override suspend fun events(onChange: suspend (path: String, deleted: Boolean) -> Unit) {
        streamOpened++
        requireOnline()
        if (dropStream) throw RuntimeException("stream dropped")
        pending.forEach { (path, deleted) -> onChange(path, deleted) }
        pending.clear()
    }

    override suspend fun delete(path: String, ifMatch: String): Boolean {
        requireOnline()
        files.remove(path)
        generation++
        return true
    }
}
