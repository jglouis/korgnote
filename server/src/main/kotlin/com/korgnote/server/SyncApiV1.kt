package com.korgnote.server

import com.korgnote.common.sync.Manifest
import com.korgnote.common.sync.ManifestEntry
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.log
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@Serializable
private data class BundleLine(
    val path: String,
    val etag: String,
    val text: String,
)

@Serializable
private data class BundleTruncated(val truncated: Boolean = true, val generation: Long)

@Serializable
private data class EtagResponse(val etag: String)

/** Beyond this, `/bundle` stops and lets the client page with `since`. */
private const val BUNDLE_BYTE_BUDGET = 8 * 1024 * 1024

/**
 * The sync API described in `docs/05-sync-protocol.md`.
 *
 * Everything is conditional and path-addressed: clients diff a manifest, fetch what they lack, and
 * push with the etag they edited. The old endpoints stay alongside until the client cuts over
 * in T10.
 */
fun Route.syncApiV1(
    store: FileStore,
    journal: ChangeJournal,
    /**
     * How often a quiet change stream says it is still there. A connection that died without a word
     * (a phone asleep, or moved to another network) looks exactly like one with nothing to say; the
     * client takes three missed beats as the former, and reconnects.
     */
    heartbeatEvery: Duration = 30.seconds,
) {
    val json = Json { encodeDefaults = true }

    route("/v1") {
        get("/manifest") {
            val since = call.request.queryParameters["since"]?.toLongOrNull()
            val delta = since?.let { journal.changesSince(it) }

            if (delta != null) {
                // The journal could answer: send only what moved.
                val entries = onDisk {
                    delta.changes.filterNot { it.deleted }.mapNotNull { entry ->
                        store.meta(entry.path)?.let {
                            ManifestEntry(it.path, it.size, it.mtimeMillis, it.etag)
                        }
                    }
                }
                call.respond(
                    Manifest(
                        generation = delta.generation,
                        complete = false,
                        entries = entries,
                        deleted = delta.changes.filter { it.deleted }.map { it.path },
                    ),
                )
                return@get
            }

            // Before the walk, so that a change landing during it is reported again rather than lost.
            val generation = journal.generation
            val entries = onDisk {
                store.list().map { ManifestEntry(it.path, it.size, it.mtimeMillis, it.etag) }
            }
            call.respond(Manifest(generation = generation, complete = true, entries = entries))
        }

        get("/bundle") {
            val since = call.request.queryParameters["since"]?.toLongOrNull()
            val delta = since?.let { journal.changesSince(it) }
            val paths = delta?.changes?.filterNot { it.deleted }?.map { it.path }
                ?: onDisk { store.list().map { it.path } }

            // Streamed, not assembled: the whole library is megabytes and need not be held twice.
            call.respondTextWriter(ContentType.Application.Json) {
                var budget = BUNDLE_BYTE_BUDGET
                for (path in paths) {
                    val file = onDisk { store.readWithMeta(path) } ?: continue
                    val text = file.text
                    if (budget - text.length < 0) {
                        val marker = json.encodeToString(BundleTruncated(generation = journal.generation))
                        withContext(Dispatchers.IO) { write(marker); write("\n") }
                        break
                    }
                    budget -= text.length
                    val line = json.encodeToString(BundleLine(path, file.meta.etag, text))
                    withContext(Dispatchers.IO) {
                        write(line)
                        write("\n")
                        flush()
                    }
                }
            }
        }

        get("/events") {
            call.response.header(HttpHeaders.CacheControl, "no-cache")
            call.respondTextWriter(ContentType.Text.EventStream) {
                // Each frame is built in memory and then written on the IO dispatcher: writing to a
                // slow client blocks, and that must not happen on a thread serving other requests.
                // One frame at a time, since the heartbeat writes from its own coroutine.
                val writing = Mutex()
                suspend fun send(frame: String) = writing.withLock {
                    withContext(Dispatchers.IO) {
                        write(frame)
                        flush()
                    }
                }

                // A hint stream: clients reconcile with the manifest, so a missed event is a delay,
                // never a lost edit.
                send(": connected\n\n")
                coroutineScope {
                    // A comment line, which SSE clients skip; ending with the stream, as the client
                    // leaving fails the next write.
                    launch {
                        while (true) {
                            delay(heartbeatEvery)
                            send(": ping\n\n")
                        }
                    }
                    journal.events.collect { entry ->
                        val name = if (entry.deleted) "deleted" else "changed"
                        send("event: $name\ndata: ${json.encodeToString(entry)}\n\n")
                    }
                }
            }
        }

        route("/files/{path...}") {
            get {
                val path = call.pathSegments()
                // A client that has a version is answered from the hash, without reading the file.
                val known = call.request.header(HttpHeaders.IfNoneMatch)?.trim('"')
                if (known != null && onDisk { store.meta(path) }?.etag == known) {
                    call.response.header(HttpHeaders.ETag, known)
                    call.respond(HttpStatusCode.NotModified)
                    return@get
                }
                val file = onDisk { store.readWithMeta(path) }
                if (file == null) {
                    call.respondText("Not found: $path", status = HttpStatusCode.NotFound)
                    return@get
                }
                call.response.header(HttpHeaders.ETag, file.meta.etag)
                call.respondText(file.text, ContentType.Text.Plain)
            }

            put {
                val path = call.pathSegments()
                val ifMatch = call.request.header(HttpHeaders.IfMatch)?.trim('"')
                val mustNotExist = call.request.header(HttpHeaders.IfNoneMatch)?.trim() == "*"
                val content = call.receiveText()

                call.respondTo(onDisk { store.put(path, content, ifMatch, mustNotExist) }, path) { meta ->
                    journal.record(path, meta.etag)
                    call.response.header(HttpHeaders.ETag, meta.etag)
                    call.respond(EtagResponse(meta.etag))
                }
            }

            delete {
                val path = call.pathSegments()
                val ifMatch = call.request.header(HttpHeaders.IfMatch)?.trim('"')
                if (ifMatch == null) {
                    call.respondText(
                        "If-Match is required to delete",
                        status = HttpStatusCode.UnprocessableEntity,
                    )
                    return@delete
                }
                call.respondTo(onDisk { store.delete(path, ifMatch) }, path) {
                    journal.record(path, etag = null, deleted = true)
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
    }
}

/** [written] when the write happened; a save and a delete that did not come back the same way. */
private suspend fun ApplicationCall.respondTo(result: WriteResult, path: String, written: suspend (FileMeta) -> Unit) {
    when (result) {
        is WriteResult.Written -> written(result.meta)
        // 409, not 412: the versions can still be merged, so the client should fetch and try again
        // rather than treat this as a failed precondition.
        is WriteResult.Conflict -> {
            response.header(HttpHeaders.ETag, result.current.etag)
            respondText("Conflict: the file changed on the server", status = HttpStatusCode.Conflict)
        }
        WriteResult.NotFound -> respondText("Not found: $path", status = HttpStatusCode.NotFound)
        WriteResult.Invalid -> respondText("invalid path", status = HttpStatusCode.UnprocessableEntity)
        WriteResult.TooLarge -> respondText("file too large", status = HttpStatusCode.PayloadTooLarge)
        is WriteResult.Failed -> {
            application.log.error("Could not change $path", result.cause)
            respondText("Could not change $path on the server; its log says why.", status = HttpStatusCode.InternalServerError)
        }
        WriteResult.ReadOnly -> respondText("Read-only library", status = HttpStatusCode.Forbidden)
    }
}

/** The store reads and writes files, which blocks; that must not happen on a thread serving requests. */
private suspend fun <T> onDisk(work: () -> T): T = withContext(Dispatchers.IO) { work() }

private fun ApplicationCall.pathSegments(): String =
    parameters.getAll("path")?.joinToString("/").orEmpty()
