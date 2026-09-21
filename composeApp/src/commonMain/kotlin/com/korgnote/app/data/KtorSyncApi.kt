package com.korgnote.app.data

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
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readLine
import kotlinx.serialization.Serializable

@Serializable
private data class WireEntry(val path: String, val size: Long, val mtime: Long, val etag: String)

@Serializable
private data class WireManifest(
    val generation: Long,
    val complete: Boolean,
    val entries: List<WireEntry>,
    val deleted: List<String> = emptyList(),
)

/**
 * The sync API v1 over HTTP.
 *
 * Nothing here parses Org: the client receives text and etags, and the parsing happens locally
 * against the replica (D2, D3).
 */
class KtorSyncApi(
    private val client: HttpClient,
    private val baseUrlProvider: () -> String,
    private val tokenProvider: () -> String = { "" },
) : SyncApi {

    private fun url(suffix: String) = "${baseUrlProvider()}/api/v1$suffix"

    private fun HttpRequestBuilder.authorize() {
        val token = tokenProvider().trim()
        if (token.isNotEmpty()) header(HttpHeaders.Authorization, "Bearer $token")
    }

    override suspend fun manifest(since: Long?): RemoteManifest {
        val suffix = if (since != null) "/manifest?since=$since" else "/manifest"
        val wire: WireManifest = client.get(url(suffix)) { authorize() }.body()
        return RemoteManifest(
            generation = wire.generation,
            complete = wire.complete,
            entries = wire.entries.map { RemoteEntry(it.path, it.etag, it.size, it.mtime) },
            deleted = wire.deleted,
        )
    }

    override suspend fun fetch(path: String): RemoteFile? {
        val response = client.get(url("/files/$path")) { authorize() }
        if (response.status != HttpStatusCode.OK) return null
        val etag = response.headers[HttpHeaders.ETag]?.trim('"') ?: return null
        return RemoteFile(path, etag, response.bodyAsText())
    }

    override suspend fun push(path: String, content: String, ifMatch: String?): PushResult {
        val response = client.put(url("/files/$path")) {
            authorize()
            if (ifMatch != null) header(HttpHeaders.IfMatch, ifMatch)
            setBody(content)
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

    override suspend fun events(onChange: suspend (path: String, deleted: Boolean) -> Unit) {
        client.prepareGet(url("/events")) {
            authorize()
            // The stream is meant to stay open. Without this the client's request timeout kills it
            // after a few seconds, and the Android engine then fails while draining the chunked
            // body ("Unbalanced enter/exit"), so live updates stop and the log fills with stacks.
            timeout {
                requestTimeoutMillis = INFINITE
                socketTimeoutMillis = INFINITE
            }
        }.execute { response ->
            val channel = response.bodyAsChannel()
            var deleted = false
            while (true) {
                val line = channel.readLine() ?: break
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

    override suspend fun delete(path: String, ifMatch: String): Boolean {
        val response = client.delete(url("/files/$path")) {
            authorize()
            header(HttpHeaders.IfMatch, ifMatch)
        }
        return response.status == HttpStatusCode.NoContent
    }
}

private val PATH_IN_FRAME = Regex("\"path\"\\s*:\\s*\"([^\"]+)\"")

/** Ktor spells "no timeout" as Long.MAX_VALUE. */
private const val INFINITE = Long.MAX_VALUE
