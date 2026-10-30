package com.korgnote.app.data

import com.korgnote.common.replica.DeleteResult
import com.korgnote.common.replica.PushResult
import com.korgnote.common.replica.RemoteEntry
import com.korgnote.common.replica.RemoteFile
import com.korgnote.common.replica.RemoteManifest
import com.korgnote.common.replica.SyncApi
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.plugins.timeout
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.encodeURLPathPart
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readLine
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.data_not_paired
import korgnote.composeapp.generated.resources.data_pairing_revoked
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.getString
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@Serializable
private data class WireEntry(val path: String, val size: Long, val mtime: Long, val etag: String)

@Serializable
private data class WireManifest(
    val generation: Long,
    val complete: Boolean,
    val entries: List<WireEntry>,
    val deleted: List<String> = emptyList(),
)

/** A line of `GET /bundle`: a file, or the marker that the server stopped short. */
@Serializable
private data class WireBundleLine(
    val path: String? = null,
    val etag: String? = null,
    val text: String? = null,
    val truncated: Boolean = false,
)

private val bundleJson = Json { ignoreUnknownKeys = true }

/**
 * The sync API v1 over HTTP.
 *
 * Nothing here parses Org: the client receives text and etags, and the parsing happens locally
 * against the replica (D2, D3).
 *
 * A paired computer may be at any of several addresses, so each request goes to the one that
 * answered last, and on to the others when that one cannot be reached.
 */
class KtorSyncApi(
    private val client: HttpClient,
    /**
     * How long a change stream that has been sending heartbeats may stay silent before it is taken
     * for dead: three of the server's 30-second beats.
     */
    private val silenceLimit: Duration = 90.seconds,
    /** Read on every request, so that pairing or a change in Settings applies at once. */
    private val endpoints: () -> List<Endpoint>,
) : SyncApi {

    private var lastReached: String? = null

    /**
     * [request] sent to each endpoint in turn, the one reached last first, until one answers; what
     * stopped the last one otherwise. An answer of any status counts: only an address that cannot
     * be reached is worth trying another for.
     */
    private suspend fun <T> reach(request: suspend (api: String, token: String?) -> T): T {
        val ordered = endpoints().sortedBy { if (it.baseUrl == lastReached) 0 else 1 }
        var failure: Exception? = null
        for (endpoint in ordered) {
            try {
                return request("${endpoint.baseUrl}/api/v1", endpoint.token).also { lastReached = endpoint.baseUrl }
            } catch (e: CancellationException) {
                throw e
            } catch (e: NotPairedException) {
                // Reached, and refused: another address is the same server, and would refuse too.
                throw e
            } catch (e: StreamLostException) {
                // Reached, and then lost: the caller must catch up on what the stream missed, which
                // a new stream at another address would leave it believing it had not.
                throw e
            } catch (e: Exception) {
                failure = e
            }
        }
        throw failure ?: NotPairedException(getString(Res.string.data_not_paired))
    }

    /** This response, unless it is the server refusing this device, which is worth saying as that. */
    private suspend fun HttpResponse.unlessRefused(): HttpResponse {
        if (status == HttpStatusCode.Unauthorized) {
            throw NotPairedException(bodyAsText().ifBlank { getString(Res.string.data_pairing_revoked) })
        }
        return this
    }

    /**
     * A file's path under the API, each segment percent-encoded: a note named with a space, `#` or
     * `?` would otherwise address some other path, or none.
     */
    private fun filePath(path: String) = "/files/" + path.split('/').joinToString("/") { it.encodeURLPathPart() }

    private fun HttpRequestBuilder.authorize(token: String?) {
        if (!token.isNullOrBlank()) header(HttpHeaders.Authorization, "Bearer ${token.trim()}")
    }

    override suspend fun manifest(since: Long?): RemoteManifest {
        val suffix = if (since != null) "/manifest?since=$since" else "/manifest"
        val wire: WireManifest = reach { api, token -> client.get(api + suffix) { authorize(token) }.unlessRefused().body() }
        return RemoteManifest(
            generation = wire.generation,
            complete = wire.complete,
            entries = wire.entries.map { RemoteEntry(it.path, it.etag, it.size, it.mtime) },
            deleted = wire.deleted,
        )
    }

    override suspend fun fetch(path: String): RemoteFile? {
        val response = reach { api, token -> client.get(api + filePath(path)) { authorize(token) }.unlessRefused() }
        if (response.status != HttpStatusCode.OK) return null
        val etag = response.headers[HttpHeaders.ETag]?.trim('"') ?: return null
        return RemoteFile(path, etag, response.bodyAsText())
    }

    /**
     * `GET /bundle`: one JSON line per file, until the server's size cap, where a line saying
     * `truncated` ends it. What it leaves out the engine fetches one by one.
     */
    override suspend fun bundle(since: Long?): List<RemoteFile> {
        val suffix = if (since != null) "/bundle?since=$since" else "/bundle"
        val body = reach { api, token -> client.get(api + suffix) { authorize(token) }.unlessRefused() }
            .takeIf { it.status == HttpStatusCode.OK }?.bodyAsText() ?: return emptyList()
        return body.lineSequence()
            .filter { it.isNotBlank() }
            .map { bundleJson.decodeFromString<WireBundleLine>(it) }
            .takeWhile { !it.truncated }
            .mapNotNull { line -> line.path?.let { path -> RemoteFile(path, line.etag ?: return@let null, line.text ?: return@let null) } }
            .toList()
    }

    override suspend fun push(
        path: String,
        content: String,
        ifMatch: String?,
        mustNotExist: Boolean,
    ): PushResult {
        val response = reach { api, token ->
            client.put(api + filePath(path)) {
                authorize(token)
                if (ifMatch != null) header(HttpHeaders.IfMatch, ifMatch)
                if (mustNotExist) header(HttpHeaders.IfNoneMatch, "*")
                setBody(content)
            }.unlessRefused()
        }
        return when (response.status) {
            HttpStatusCode.OK ->
                response.headers[HttpHeaders.ETag]?.trim('"')
                    ?.let { PushResult.Pushed(it) }
                    ?: PushResult.Failed("no etag in response")
            // Mergeable, not fatal: the engine keeps the local version and flags it.
            HttpStatusCode.Conflict -> PushResult.Conflict(response.headers[HttpHeaders.ETag]?.trim('"'))
            else -> PushResult.Failed("${response.status}")
        }
    }

    override suspend fun events(onChange: suspend (path: String, deleted: Boolean) -> Unit) = reach { api, token ->
        var opened = false
        try {
            streamEvents(api, token, onOpened = { opened = true }, onChange)
        } catch (e: Exception) {
            // Once open, a failure is the server lost, not an address it is not at: see reach.
            if (!opened || e is CancellationException || e is NotPairedException || e is StreamLostException) throw e
            throw StreamLostException("The change stream was cut: ${e.message}", e)
        }
    }

    private suspend fun streamEvents(
        api: String,
        token: String?,
        onOpened: () -> Unit,
        onChange: suspend (path: String, deleted: Boolean) -> Unit,
    ) {
        client.prepareGet("$api/events") {
            authorize(token)
            // The stream is meant to stay open. Without this the client's request timeout kills it
            // after a few seconds, and the Android engine then fails while draining the chunked
            // body ("Unbalanced enter/exit"), so live updates stop and the log fills with stacks.
            timeout {
                requestTimeoutMillis = INFINITE
                socketTimeoutMillis = INFINITE
            }
        }.execute { response ->
            val channel = response.unlessRefused().bodyAsChannel()
            onOpened()
            var deleted = false
            // A connection can die without a word, and then a read waits forever: the app would
            // show stale notes until something else made it sync. A server that sends heartbeats
            // makes that silence detectable; one too old to send them is waited on as before.
            var heartbeats = false
            while (true) {
                val line = try {
                    withTimeout(if (heartbeats) silenceLimit else Duration.INFINITE) { channel.readLine() }
                } catch (_: TimeoutCancellationException) {
                    throw StreamLostException("No heartbeat on the change stream for $silenceLimit")
                } ?: break
                if (line.startsWith(": ping")) heartbeats = true
                when {
                    line.startsWith("event:") -> deleted = line.contains("deleted")
                    line.startsWith("data:") -> {
                        // The frame carries the etag too, but the engine re-fetches anyway: the
                        // stream is a hint, and the file may have moved again since.
                        val path = PATH_IN_FRAME.find(line)?.groupValues?.get(1)
                        if (path != null) onChange(path, deleted)
                    }
                }
            }
        }
    }

    override suspend fun delete(path: String, ifMatch: String): DeleteResult {
        val response = reach { api, token ->
            client.delete(api + filePath(path)) {
                authorize(token)
                header(HttpHeaders.IfMatch, ifMatch)
            }.unlessRefused()
        }
        return when (response.status) {
            HttpStatusCode.NoContent -> DeleteResult.Deleted
            HttpStatusCode.Conflict -> DeleteResult.Conflict(response.headers[HttpHeaders.ETag]?.trim('"'))
            HttpStatusCode.NotFound -> DeleteResult.Gone
            else -> DeleteResult.Failed("${response.status}")
        }
    }
}

/** This device is not paired: it never was, or the server answered and refused it, having revoked it. */
class NotPairedException(message: String) : Exception(message)

/**
 * The change stream was open, and then failed or fell silent, its heartbeats stopping: the server was
 * reached and is presumed lost. Not a cancellation, so that the caller reconnects, and catches up on
 * what the stream missed meanwhile.
 */
class StreamLostException(message: String, cause: Throwable? = null) : Exception(message, cause)

private val PATH_IN_FRAME =Regex("\"path\"\\s*:\\s*\"([^\"]+)\"")

/** Ktor spells "no timeout" as Long.MAX_VALUE. */
private const val INFINITE = Long.MAX_VALUE
