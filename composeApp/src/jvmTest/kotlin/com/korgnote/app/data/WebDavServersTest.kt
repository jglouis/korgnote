package com.korgnote.app.data

import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.InMemoryReplicaStorage
import com.korgnote.common.replica.SyncEngine
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.http.HttpMethod
import io.ktor.util.encodeBase64
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The experimental WebDAV sync against real servers, two devices at a time, as the engine's own
 * tests do against a fake one.
 *
 * Runs only where `KORGNOTE_WEBDAV_SERVERS` names some, as `name=url;name=url`, all signing in as
 * korg / korgnote-test: `scripts/webdav-test-servers.sh` starts them in Docker and prints the line.
 * Each test works in a folder of its own under each server's, so runs never meet.
 */
class WebDavServersTest {

    private val servers: Map<String, String> = System.getenv("KORGNOTE_WEBDAV_SERVERS").orEmpty()
        .split(';').filter { it.contains('=') }
        .associate { it.substringBefore('=') to it.substringAfter('=').trimEnd('/') }

    /** The servers expected to pass [WebDavSyncApi.check]; the others are expected to be refused. */
    private val safe = setOf("apache", "nextcloud")

    private val client = HttpClient(Java) { expectSuccess = false }

    /** A fresh, empty folder on the server at [url], as an account. */
    private suspend fun freshFolder(url: String): WebDavAccount {
        val folder = "$url/run-${Random.nextLong().toULong().toString(16)}"
        client.request("$folder/") {
            method = HttpMethod("MKCOL")
            header("Authorization", "Basic " + "korg:korgnote-test".encodeBase64())
        }
        return WebDavAccount(folder, "korg", "korgnote-test")
    }

    private class Device(account: WebDavAccount, client: HttpClient) {
        val api = WebDavSyncApi(client, { account })
        val store = DocumentStore(InMemoryReplicaStorage())
        private var stamp = 0
        val engine = SyncEngine(store, api) { "20261029-1200${(stamp++).toString().padStart(2, '0')}" }
    }

    /** [block] on each server that sync is expected to be safe with, naming the one that failed. */
    private fun eachSafeServer(block: suspend (WebDavAccount) -> Unit) = runBlocking {
        val usable = servers.filterKeys { it in safe }
        if (usable.isEmpty()) {
            println("No WebDAV servers to test against: see scripts/webdav-test-servers.sh")
            return@runBlocking
        }
        for ((name, url) in usable) {
            try {
                block(freshFolder(url))
            } catch (e: Throwable) {
                throw AssertionError("On $name: ${e.message}", e)
            }
        }
    }

    @Test
    fun safeServersPassTheCheckAndOthersAreRefused() = runBlocking {
        for ((name, url) in servers) {
            val result = WebDavSyncApi(client, { WebDavAccount(url, "korg", "korgnote-test") }).check()
            println("$name: $result")
            if (name in safe) {
                assertTrue(result is WebDavCheck.Usable, "$name should be usable: $result")
            } else {
                assertTrue(result is WebDavCheck.Unusable, "$name should be refused: $result")
            }
        }
    }

    @Test
    fun aWrongPasswordIsSaidToBeOne() = runBlocking {
        for ((name, url) in servers.filterKeys { !it.endsWith("-digest") }) {
            val result = WebDavSyncApi(client, { WebDavAccount(url, "korg", "wrong") }).check()
            assertTrue(result is WebDavCheck.Unusable && "refused the user name or password" in result.reason, "$name: $result")
        }
    }

    @Test
    fun aServerAskingForDigestSaysSoRatherThanBlameThePassword() = runBlocking {
        // The right password, even: what is refused is the way of signing in.
        for ((name, url) in servers.filterKeys { it.endsWith("-digest") }) {
            val result = WebDavSyncApi(client, { WebDavAccount(url, "korg", "korgnote-test") }).check()
            println("$name: $result")
            assertTrue(result is WebDavCheck.Unusable && "with Digest" in result.reason && "refused" !in result.reason, "$name: $result")
        }
    }

    @Test
    fun notesMadeOnOneDeviceReachTheOtherAndASecondSyncDoesNothing() = eachSafeServer { account ->
        val a = Device(account, client)
        val b = Device(account, client)
        a.store.create("inbox.org", "* Inbox\n")
        a.store.create("daily/2026-10-29.org", "* Thursday\n")
        a.store.create("café notes/été à Paris.org", "* Été\n")

        assertEquals(3, a.engine.sync().pushed.size)
        val again = a.engine.sync()
        assertTrue(again.pulled.isEmpty() && again.pushed.isEmpty(), "a second sync moved something: $again")

        assertEquals(3, b.engine.sync().pulled.size)
        assertEquals("* Été\n", b.store.read("café notes/été à Paris.org"))
        assertEquals(a.store.paths().sorted(), b.store.paths().sorted())
        assertTrue(b.engine.sync().pulled.isEmpty(), "the second device pulled twice")
    }

    @Test
    fun editsToDifferentLinesAreMerged() = eachSafeServer { account ->
        val a = Device(account, client)
        val b = Device(account, client)
        a.store.create("list.org", "* One\n* Two\n* Three\n")
        a.engine.sync()
        b.engine.sync()

        a.store.save("list.org", "* One, from A\n* Two\n* Three\n")
        b.store.save("list.org", "* One\n* Two\n* Three, from B\n")
        a.engine.sync()
        assertEquals(listOf("list.org"), b.engine.sync().merged)
        a.engine.sync()

        val merged = "* One, from A\n* Two\n* Three, from B\n"
        assertEquals(merged, a.store.read("list.org"))
        assertEquals(merged, b.store.read("list.org"))
    }

    @Test
    fun editsToTheSameLineKeepTheServersAndACopy() = eachSafeServer { account ->
        val a = Device(account, client)
        val b = Device(account, client)
        a.store.create("todo.org", "* TODO Call\n")
        a.engine.sync()
        b.engine.sync()

        a.store.save("todo.org", "* DONE Call\n")
        b.store.save("todo.org", "* WAITING Call\n")
        a.engine.sync()
        assertEquals(listOf("todo.org"), b.engine.sync().conflicted)

        assertEquals("* DONE Call\n", b.store.read("todo.org"))
        val copy = b.store.paths().single { "sync-conflict" in it }
        assertEquals("* WAITING Call\n", b.store.read(copy))
        // The copy is a note like any other, and reaches the first device.
        b.engine.sync()
        a.engine.sync()
        assertEquals("* WAITING Call\n", a.store.read(copy))
    }

    @Test
    fun anEditOfTheSameSizeAtOnceIsNeverWrittenOver() = eachSafeServer { account ->
        // TODO to DONE is the same size. Nextcloud gives a file the same etag for the same size
        // in the same second, so without care the second device would never see the edit, and
        // would write over it.
        val a = Device(account, client)
        val b = Device(account, client)
        a.store.create("task.org", "* TODO Call\n")
        a.engine.sync()
        b.engine.sync()

        a.store.save("task.org", "* DONE Call\n")
        a.engine.sync()
        b.store.save("task.org", "* TODO Call back\n")
        b.engine.sync()
        // A conflict copy made in one cycle goes up in the next.
        b.engine.sync()
        a.engine.sync()

        for (device in listOf(a, b)) {
            val everything = device.store.paths().joinToString("") { runBlocking { device.store.read(it).orEmpty() } }
            assertTrue("DONE Call" in everything && "Call back" in everything, everything)
        }
    }

    @Test
    fun aDeletionReachesTheOtherDevice() = eachSafeServer { account ->
        val a = Device(account, client)
        val b = Device(account, client)
        a.store.create("old.org", "* Old\n")
        a.engine.sync()
        b.engine.sync()

        a.store.delete("old.org")
        a.engine.sync()
        b.engine.sync()
        assertNull(b.store.read("old.org"))
        assertTrue(Device(account, client).api.manifest().entries.none { it.path == "old.org" })
    }

    @Test
    fun aNoteMadeOnBothDevicesAtOnceLosesNeither() = eachSafeServer { account ->
        val a = Device(account, client)
        val b = Device(account, client)
        a.store.create("same.org", "* From A\n")
        b.store.create("same.org", "* From B\n")
        a.engine.sync()
        b.engine.sync()
        a.engine.sync()

        // The engine merges the two, as it does with the KorgNote server; what matters here is
        // that the creation on B did not replace A's, nor the other way round.
        for (device in listOf(a, b)) {
            val everything = device.store.paths().joinToString("") { runBlocking { device.store.read(it).orEmpty() } }
            assertTrue("From A" in everything && "From B" in everything, everything)
        }
    }

    @Test
    fun aCreationNeverReplacesANoteMadeElsewhere() = eachSafeServer { account ->
        // What the engine relies on when its manifest is out of date: If-None-Match: *.
        val a = Device(account, client)
        val b = Device(account, client)
        a.store.create("race.org", "* From A\n")
        a.engine.sync()
        assertTrue(b.api.push("race.org", "* From B\n", ifMatch = null, mustNotExist = true) is com.korgnote.common.replica.PushResult.Conflict)
        assertEquals("* From A\n", b.api.fetch("race.org")?.text)
    }

    @Test
    fun aWriteOverAnOldVersionIsRefused() = eachSafeServer { account ->
        val a = Device(account, client)
        a.store.create("versions.org", "* One\n")
        a.engine.sync()
        val old = a.api.fetch("versions.org")!!.etag
        a.store.save("versions.org", "* Two\n")
        a.engine.sync()
        val stale = a.api.push("versions.org", "* Stale\n", ifMatch = old)
        assertTrue(stale is com.korgnote.common.replica.PushResult.Conflict, "a write over $old: $stale")
        val gone = a.api.delete("versions.org", ifMatch = old)
        assertTrue(gone is com.korgnote.common.replica.DeleteResult.Conflict, "a delete over $old: $gone")
        assertEquals("* Two\n", a.api.fetch("versions.org")?.text)
    }

    @Test
    fun aDeletionNeverRemovesAnEditMadeElsewhere() = eachSafeServer { account ->
        val a = Device(account, client)
        val b = Device(account, client)
        a.store.create("kept.org", "* Kept\n")
        a.engine.sync()
        b.engine.sync()

        a.store.save("kept.org", "* Kept, and edited\n")
        a.engine.sync()
        b.store.delete("kept.org")
        b.engine.sync()

        val fresh = Device(account, client)
        fresh.engine.sync()
        assertEquals("* Kept, and edited\n", fresh.store.read("kept.org"))
    }

    @Test
    fun hiddenFilesAndOtherFilesAreLeftAlone() = eachSafeServer { account ->
        val a = Device(account, client)
        a.store.create("note.org", "* Note\n")
        a.engine.sync()
        // Written behind the engine's back, as another app would.
        for (path in listOf(".hidden.org", ".git/config.org", "picture.png")) {
            val pushed = a.api.push(path, "x", ifMatch = null, mustNotExist = true)
            if (pushed !is com.korgnote.common.replica.PushResult.Pushed) fail("could not write $path: $pushed")
        }
        assertEquals(listOf("note.org"), a.api.manifest().entries.map { it.path })
    }
}
