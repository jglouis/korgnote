package com.korgnote.app.data

import com.korgnote.common.replica.DeleteResult
import com.korgnote.common.replica.PushResult
import com.korgnote.common.replica.RemoteEntry
import com.korgnote.common.replica.RemoteFile
import com.korgnote.common.replica.RemoteManifest
import com.korgnote.common.replica.SyncApi
import com.korgnote.common.replica.isListedNotePath
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import io.ktor.http.decodeURLPart
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import io.ktor.http.withCharset
import io.ktor.util.encodeBase64
import io.ktor.utils.io.charsets.Charsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** What [WebDavSyncApi.check] found at the address the user gave. */
sealed interface WebDavCheck {
    /** Safe to sync with; it holds [notes] notes already. */
    data class Usable(val notes: Int) : WebDavCheck

    data class Unusable(val reason: String) : WebDavCheck
}

/**
 * The sync API over a plain WebDAV folder — Nextcloud, a NAS, a hosting service — instead of the
 * KorgNote server. Experimental.
 *
 * The engine needs nothing WebDAV lacks: a listing with an etag per file (`PROPFIND`), and writes
 * and deletes that only land over the version they were made against (`If-Match`,
 * `If-None-Match: *`). What WebDAV has no word for is left at the interface's defaults: there is
 * no generation, so every manifest is complete; no bundle, so files are fetched one by one; and no
 * change stream, so [events] waits and lets the caller look again. A server that ignores the
 * conditions would let one device overwrite another's edit without anyone seeing it, which is what
 * [check] is for, before anything syncs.
 *
 * Etags are kept without their quotes, as the KorgNote server's are, and quoted again on the way
 * out: some servers quote them in a listing and not in a header, or the other way round.
 */
class WebDavSyncApi(
    private val client: HttpClient,
    /** Read on every request, so a change in Settings applies at once. */
    private val account: () -> WebDavAccount?,
    /** How long [events] waits before the caller looks for changes again. */
    private val pollEvery: Duration = 30.seconds,
) : SyncApi {

    /** The same client, for [account] instead: to [check] a server before it is saved in settings. */
    fun trying(account: WebDavAccount) = WebDavSyncApi(client, { account }, pollEvery)

    private fun current(): WebDavAccount =
        account() ?: throw WebDavRefusedException("No WebDAV server is set up yet. Set one up in Settings.")

    /** The folder's URL, ending in `/`. */
    private fun WebDavAccount.base(): String = url.trim().trimEnd('/') + "/"

    /** [path] under the folder, each segment percent-encoded; with [folder], ending in `/`. */
    private fun WebDavAccount.urlOf(path: String, folder: Boolean = false): String {
        if (path.isEmpty()) return base()
        val encoded = path.split('/').joinToString("/") { it.encodeURLPathPart() }
        return base() + encoded + if (folder) "/" else ""
    }

    private fun HttpRequestBuilder.signIn(account: WebDavAccount) {
        if (account.username.isNotEmpty() || account.password.isNotEmpty()) {
            header(HttpHeaders.Authorization, "Basic " + "${account.username}:${account.password}".encodeBase64())
        }
    }

    private suspend fun send(method: HttpMethod, path: String, folder: Boolean = false, configure: HttpRequestBuilder.() -> Unit = {}): HttpResponse {
        val account = current()
        val response = client.request(account.urlOf(path, folder)) {
            this.method = method
            signIn(account)
            configure()
        }
        if (response.status == HttpStatusCode.Unauthorized) {
            throw WebDavRefusedException("The WebDAV server refused the user name or password. Check them in Settings.")
        }
        return response
    }

    // --- Listing ---

    /** One item of a `PROPFIND`: a note, or a folder to look in. */
    private data class DavItem(val path: String, val folder: Boolean, val etag: String?, val size: Long)

    private suspend fun propfind(path: String, depth: String, folder: Boolean = true): List<DavItem> {
        val response = send(PROPFIND, path, folder) {
            header("Depth", depth)
            setBody(TextContent(PROPFIND_BODY, ContentType.Application.Xml.withCharset(Charsets.UTF_8)))
        }
        if (response.status.value != 207) {
            throw WebDavException(
                if (response.status == HttpStatusCode.NotFound) "There is no folder at ${current().url}."
                else "The WebDAV server answered ${response.status} when listing ${path.ifEmpty { "the notes folder" }}."
            )
        }
        val basePath = decodedPath(current().base())
        return parseDavXml(response.bodyAsText()).child("multistatus")?.all("response").orEmpty().mapNotNull { item ->
            val href = item.child("href")?.text ?: return@mapNotNull null
            val relative = relativePath(href, basePath) ?: return@mapNotNull null
            // Only what the server could read: a property it has not got comes back under a 404.
            val props = item.all("propstat").filter { it.child("status")?.text?.contains(" 200") ?: true }
                .mapNotNull { it.child("prop") }
            fun prop(name: String) = props.firstNotNullOfOrNull { it.child(name) }
            DavItem(
                path = relative,
                folder = prop("resourcetype")?.child("collection") != null,
                etag = prop("getetag")?.text?.takeIf { it.isNotEmpty() }?.let(::canonicalEtag),
                size = prop("getcontentlength")?.text?.toLongOrNull() ?: 0,
            )
        }
    }

    /**
     * Every note in the folder and the folders under it, the hidden ones aside, as the KorgNote
     * server lists them. Walked one level at a time, since many servers refuse `Depth: infinity`.
     *
     * Always complete, so it must never leave a note out by mistake: the engine would take that for
     * a deletion. Any folder that cannot be listed fails the whole manifest instead.
     */
    override suspend fun manifest(since: Long?): RemoteManifest {
        val entries = mutableListOf<RemoteEntry>()
        val folders = ArrayDeque(listOf(""))
        while (folders.isNotEmpty()) {
            val folder = folders.removeFirst()
            for (item in propfind(folder, depth = "1")) {
                if (item.path == folder || item.path.isEmpty()) continue
                if (item.folder) {
                    if (!item.path.substringAfterLast('/').startsWith('.')) folders += item.path
                } else if (isListedNotePath(item.path)) {
                    val etag = item.etag ?: throw WebDavException("The WebDAV server lists ${item.path} without an etag.")
                    entries += RemoteEntry(item.path, etag, item.size)
                }
            }
        }
        return RemoteManifest(generation = 0, complete = true, entries = entries)
    }

    // --- Files ---

    /**
     * [path] with a strong etag, which is the only kind `If-Match` can match. Apache gives a weak
     * one for the second after a file changed, so a weak one is waited out and read again.
     */
    override suspend fun fetch(path: String): RemoteFile? {
        repeat(WEAK_ETAG_RETRIES) {
            val file = fetchOnce(path) ?: return null
            if (!isWeak(file.etag)) return file
            delay(WEAK_ETAG_WAIT)
        }
        return fetchOnce(path)
    }

    private suspend fun fetchOnce(path: String): RemoteFile? {
        val response = send(HttpMethod.Get, path)
        if (response.status != HttpStatusCode.OK) return null
        val etag = response.headers[HttpHeaders.ETag]?.let(::canonicalEtag)
            ?: throw WebDavException("The WebDAV server sent $path without an etag.")
        return RemoteFile(path, etag, response.bodyAsText(Charsets.UTF_8))
    }

    private suspend fun put(path: String, content: String, ifMatch: String?, mustNotExist: Boolean) =
        send(HttpMethod.Put, path) {
            if (ifMatch != null) header(HttpHeaders.IfMatch, quoted(ifMatch))
            if (mustNotExist) header(HttpHeaders.IfNoneMatch, "*")
            setBody(TextContent(content, ContentType.Text.Plain.withCharset(Charsets.UTF_8)))
        }

    override suspend fun push(path: String, content: String, ifMatch: String?, mustNotExist: Boolean): PushResult {
        var response = put(path, content, ifMatch, mustNotExist)
        // WebDAV's 409 is not an edit conflict: a folder above the file is missing. Nextcloud says
        // so with a 404 instead.
        val folderMissing = response.status == HttpStatusCode.Conflict || response.status == HttpStatusCode.NotFound
        if (folderMissing && path.contains('/')) {
            makeFolders(path.substringBeforeLast('/'))
            response = put(path, content, ifMatch, mustNotExist)
        }
        if (!response.status.isSuccess()) {
            return if (response.status == HttpStatusCode.PreconditionFailed) PushResult.Conflict(null)
            else PushResult.Failed("${response.status}")
        }
        var etag = etagAfterPut(path, content, response)
        // Nextcloud makes an etag of the file's size and its time to the second, so a write the same
        // size as the version it replaced, in the same second, keeps that version's etag; a device
        // still holding it could then write over this one unrefused. Written again a second later,
        // it gets an etag of its own.
        repeat(SAME_ETAG_RETRIES) {
            if (ifMatch == null || etag != canonicalEtag(ifMatch)) return PushResult.Pushed(etag)
            delay(WEAK_ETAG_WAIT)
            val again = put(path, content, ifMatch = etag, mustNotExist = false)
            // Someone wrote in between: theirs is merged with this one like any conflict.
            if (!again.status.isSuccess()) return PushResult.Conflict(null)
            etag = etagAfterPut(path, content, again)
        }
        return PushResult.Pushed(etag)
    }

    /**
     * The etag of what was just written. From the answer when it says; otherwise read back, and
     * trusted only if it is still what was written. When someone wrote over it in between, an etag
     * matching nothing makes the next sync fetch theirs, rather than take it for ours.
     */
    private suspend fun etagAfterPut(path: String, content: String, response: HttpResponse): String {
        response.headers[HttpHeaders.ETag]?.let(::canonicalEtag)?.takeUnless(::isWeak)?.let { return it }
        val written = fetch(path)
        return if (written?.text == content) written.etag else UNKNOWN_ETAG
    }

    /** Creates [folder] and those above it; one already there is fine. */
    private suspend fun makeFolders(folder: String) {
        val segments = folder.split('/')
        for (i in segments.indices) {
            send(MKCOL, segments.take(i + 1).joinToString("/"), folder = true)
        }
    }

    override suspend fun delete(path: String, ifMatch: String): DeleteResult {
        val response = send(HttpMethod.Delete, path) { header(HttpHeaders.IfMatch, quoted(ifMatch)) }
        return when {
            response.status.isSuccess() -> DeleteResult.Deleted
            response.status == HttpStatusCode.PreconditionFailed -> DeleteResult.Conflict(null)
            response.status == HttpStatusCode.NotFound -> DeleteResult.Gone
            else -> DeleteResult.Failed("${response.status}")
        }
    }

    /**
     * WebDAV has no change stream: this waits [pollEvery] and ends, as a stream the server closed
     * would, and the caller's catch-up sync is the look for changes.
     */
    override suspend fun events(onChange: suspend (path: String, deleted: Boolean) -> Unit) {
        delay(pollEvery)
    }

    // --- Setting up ---

    /**
     * Whether the folder at [account] can be synced with safely, before anything is.
     *
     * Beyond signing in and listing, it writes a hidden file and checks that the server honours the
     * conditions sync relies on: a creation refused over an existing file, a write and a delete
     * refused over a version that is not there, a write accepted over the one that is. A server
     * that ignores any of these is refused here, since with it two devices could overwrite each
     * other's edits without either knowing. The file is deleted afterwards, whatever happened.
     */
    suspend fun check(): WebDavCheck {
        return try {
            val top = propfind("", depth = "0").firstOrNull()
            if (top == null || !top.folder) return WebDavCheck.Unusable("This address is not a WebDAV folder.")
            checkConditions()?.let { return WebDavCheck.Unusable(it) }
            WebDavCheck.Usable(manifest().entries.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: WebDavRefusedException) {
            WebDavCheck.Unusable(e.message.orEmpty())
        } catch (e: WebDavException) {
            WebDavCheck.Unusable(e.message.orEmpty())
        } catch (e: Exception) {
            WebDavCheck.Unusable("Cannot reach the server: ${e.message ?: e::class.simpleName}")
        }
    }

    /** Null when the server honours every condition sync needs; otherwise what it does not. */
    private suspend fun checkConditions(): String? {
        val unsafe = "KorgNote cannot sync with it safely: edits made on two devices could overwrite each other."
        send(HttpMethod.Delete, PROBE)
        try {
            val created = put(PROBE, "1", ifMatch = null, mustNotExist = true)
            if (!created.status.isSuccess()) return "KorgNote cannot write in this folder (${created.status})."
            if (put(PROBE, "2", ifMatch = null, mustNotExist = true).status != HttpStatusCode.PreconditionFailed) {
                return "The server ignores If-None-Match. $unsafe"
            }
            if (put(PROBE, "3", ifMatch = "korgnote-no-such-version", mustNotExist = false).status != HttpStatusCode.PreconditionFailed) {
                return "The server ignores If-Match. $unsafe"
            }
            val fetched = fetch(PROBE) ?: return "The server lost a file it just wrote."
            val listed = propfind(PROBE, depth = "0", folder = false).firstOrNull()?.etag
            if (listed != fetched.etag) {
                return "The server gives a file one etag when listed and another when read ($listed, ${fetched.etag}). $unsafe"
            }
            val updated = put(PROBE, "4", ifMatch = fetched.etag, mustNotExist = false)
            if (!updated.status.isSuccess()) {
                return "The server refuses a write over the file's own etag (${updated.status})."
            }
            val deleted = send(HttpMethod.Delete, PROBE) { header(HttpHeaders.IfMatch, quoted("korgnote-no-such-version")) }
            if (deleted.status != HttpStatusCode.PreconditionFailed) return "The server ignores If-Match when deleting. $unsafe"
            return null
        } finally {
            send(HttpMethod.Delete, PROBE)
        }
    }

    private companion object {
        val PROPFIND = HttpMethod("PROPFIND")
        val MKCOL = HttpMethod("MKCOL")

        val PROPFIND_BODY = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getetag/><d:getcontentlength/></d:prop></d:propfind>
        """.trimIndent()

        /** Hidden, so no listing of the notes ever shows it. */
        const val PROBE = ".korgnote-check"

        /** Matches no etag a server gives: what was written is fetched again on the next sync. */
        const val UNKNOWN_ETAG = "korgnote-unknown"

        const val WEAK_ETAG_RETRIES = 3
        const val SAME_ETAG_RETRIES = 2
        val WEAK_ETAG_WAIT = 1100.milliseconds
    }
}

private fun isWeak(etag: String) = etag.startsWith("W/")

/** A WebDAV server answered, with something sync cannot go on with. */
class WebDavException(message: String) : Exception(message)

/** An etag without its quotes; a weak one keeps its `W/`. */
internal fun canonicalEtag(raw: String): String {
    val etag = raw.trim()
    return if (etag.startsWith("W/")) "W/" + etag.removePrefix("W/").trim('"') else etag.trim('"')
}

/** [etag] as a header carries it, quoted. */
internal fun quoted(etag: String): String =
    if (etag.startsWith("W/")) "W/\"${etag.removePrefix("W/")}\"" else "\"$etag\""

/** The path of [url], decoded, ending in `/`. */
private fun decodedPath(url: String): String = decodeSegments(Url(url).encodedPath).trimEnd('/') + "/"

private fun decodeSegments(path: String): String = path.split('/').joinToString("/") { it.decodeURLPart() }

/**
 * [href] relative to the folder at [basePath], without a trailing `/`: empty for the folder itself,
 * null for anything outside it. Servers give hrefs as paths or as full URLs, encoded their own way,
 * so both sides are compared decoded.
 */
internal fun relativePath(href: String, basePath: String): String? {
    val path = decodeSegments(if (href.contains("://")) Url(href).encodedPath else href.substringBefore('?'))
    if (path.trimEnd('/') == basePath.trimEnd('/')) return ""
    if (!path.startsWith(basePath)) return null
    return path.removePrefix(basePath).trimEnd('/')
}
