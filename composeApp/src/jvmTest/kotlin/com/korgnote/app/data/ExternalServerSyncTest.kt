package com.korgnote.app.data

import com.korgnote.app.di.platformBuildHttpClient
import com.korgnote.common.pairing.PairRequest
import com.korgnote.common.pairing.PairResponse
import com.korgnote.common.pairing.PairingLink
import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.InMemoryReplicaStorage
import com.korgnote.common.replica.SyncEngine
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The app's own sync code against a sync server running as a program of its own: the HTTP client
 * the desktop app is built with, pinned to the server's certificate as a paired device's is,
 * `KtorSyncApi`, `SyncEngine` and `DocumentStore`, with nothing faked in between.
 *
 * Which server is said by the environment, so that the same tests hold the Kotlin server and the
 * Rust one to the same behaviour:
 *
 *     KORGNOTE_SERVER_UNDER_TEST=server/build/install/server/bin/server ./gradlew :composeApp:jvmTest --tests '*ExternalServerSyncTest*'
 *     KORGNOTE_SERVER_UNDER_TEST=server-rust/target/release/korgnote-server ./gradlew :composeApp:jvmTest --tests '*ExternalServerSyncTest*'
 *
 * Without it they do nothing. Every note is made up here, in a folder of the test's own.
 */
class ExternalServerSyncTest {

    private val command: String? = System.getenv("KORGNOTE_SERVER_UNDER_TEST")?.takeIf { it.isNotBlank() }
    private lateinit var dir: File
    private lateinit var notes: File
    private var httpPort = 0
    private var httpsPort = 0
    private var process: Process? = null
    private val clients = mutableListOf<HttpClient>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var stamps = 0

    @BeforeTest
    fun startTheServer() {
        if (command == null) return
        dir = Files.createTempDirectory("korgnote-external-server").toFile()
        notes = File(dir, "notes").apply { mkdirs() }
        httpPort = ServerSocket(0).use { it.localPort }
        httpsPort = ServerSocket(0).use { it.localPort }
        start()
    }

    @AfterTest
    fun stopTheServer() {
        if (command == null) return
        scope.cancel()
        clients.forEach { it.close() }
        stop()
        dir.deleteRecursively()
    }

    private fun start() {
        val executable = File(command!!).let { if (it.isAbsolute) it else File(System.getProperty("user.dir")).resolve("..").resolve(it.path).canonicalFile }
        val builder = ProcessBuilder(executable.path).directory(dir).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(File(dir, "server.log")))
        builder.environment().apply {
            keys.filter { it.startsWith("KORGNOTE_") || it == "NOTES_DIR" || it == "WASM_DIR" }.forEach(::remove)
            put("NOTES_DIR", notes.path)
            put("KORGNOTE_STATE_DIR", File(dir, "state").path)
            put("KORGNOTE_PORT", httpPort.toString())
            put("KORGNOTE_HTTPS_PORT", httpsPort.toString())
            put("KORGNOTE_ADVERTISED_ADDRESSES", "127.0.0.1")
            put("KORGNOTE_SERVER_NAME", "Made-up Laptop")
            put("KORGNOTE_DISABLE_WEB", "1")
        }
        process = builder.start()
        val deadline = System.currentTimeMillis() + 60_000
        while (true) {
            check(process!!.isAlive) { "The server stopped at startup:\n" + File(dir, "server.log").readText() }
            if (runCatching { local("GET", "/api/").first == 200 }.getOrDefault(false)) return
            check(System.currentTimeMillis() < deadline) { "The server did not answer:\n" + File(dir, "server.log").readText() }
            Thread.sleep(100)
        }
    }

    private fun stop() {
        process?.let {
            it.destroy()
            if (!it.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) it.destroyForcibly()
        }
    }

    /** A request on the plain port, as this computer makes one: its status and what it said. */
    private fun local(method: String, path: String): Pair<Int, String> {
        val connection = URI("http://127.0.0.1:$httpPort$path").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 2_000
        connection.readTimeout = 20_000
        if (method != "GET") {
            connection.doOutput = true
            connection.outputStream.close()
        }
        val status = connection.responseCode
        val body = (if (status < 400) connection.inputStream else connection.errorStream)?.readBytes()?.decodeToString().orEmpty()
        return status to body
    }

    /** A phone, as far as the server can tell: paired over TLS, with a replica of its own. */
    private inner class Device(val name: String) {
        val store = DocumentStore(InMemoryReplicaStorage())
        lateinit var api: KtorSyncApi
        lateinit var engine: SyncEngine
        var deviceId: String? = null
        var token: String? = null
        private var watching: Job? = null
        val applied = Channel<String>(Channel.UNLIMITED)

        /** Pairs as the app does: the link the server shows, traded over TLS to its certificate alone. */
        suspend fun pair() {
            val (status, text) = local("POST", "/api/local/pairing?format=link")
            assertEquals(200, status, text)
            val link = assertNotNull(PairingLink.parse(text), "not a pairing link: $text")
            assertEquals(httpsPort, link.port)
            val client = platformBuildHttpClient(CertificatePins { link.fingerprint }).also { clients += it }
            val response = client.post("https://${link.addresses.first()}:${link.port}/api/pair") {
                contentType(ContentType.Application.Json)
                setBody(PairRequest(link.code, name, deviceId, token))
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val answer = response.body<PairResponse>()
            assertEquals(link.serverId, answer.serverId)
            assertEquals("Made-up Laptop", answer.serverName)
            deviceId = answer.deviceId
            token = answer.token
            api = KtorSyncApi(client) { listOf(Endpoint("https://127.0.0.1:$httpsPort", token)) }
            engine = SyncEngine(store, api) { "20261119-10%04d".format(++stamps) }
        }

        suspend fun sync() = engine.sync()

        suspend fun notes(): Map<String, String> = store.paths().associateWith { store.read(it).orEmpty() }.toSortedMap()

        /** Follows the change stream until the test ends, saying which notes it brought in. */
        fun watch() {
            watching = scope.launch {
                while (true) {
                    runCatching { engine.watch { applied.send(it) } }
                    delay(500)
                }
            }
        }

        suspend fun awaitApplied(path: String) {
            val heard = withTimeoutOrNull(60.seconds) {
                while (applied.receive() != path) Unit
            }
            assertNotNull(heard, "$name never heard of a change to $path")
        }
    }

    /** What the server has on disk: its notes, by the rule the library goes by. */
    private fun onDisk(): Map<String, String> = notes.walkTopDown()
        .onEnter { it == notes || !it.name.startsWith(".") }
        .filter { it.isFile && it.extension == "org" }
        .associate { it.relativeTo(notes).invariantSeparatorsPath to it.readText() }
        .toSortedMap()

    private fun write(path: String, text: String) {
        File(notes, path).apply { parentFile.mkdirs() }.writeText(text)
    }

    private fun generation(): Long =
        Regex("\"generation\":(\\d+)").find(local("GET", "/api/v1/manifest").second)!!.groupValues[1].toLong()

    /**
     * An edit made on the computer, behind the server's back, and waited for: a device asking what
     * changed is told of it only once the server's watcher has noticed, which takes a moment, and on
     * macOS, where the JDK polls, several seconds.
     */
    private fun onTheComputer(path: String, change: () -> Unit) {
        val since = generation()
        change()
        val deadline = System.currentTimeMillis() + 60_000
        while ("\"$path\"" !in local("GET", "/api/v1/manifest?since=$since").second) {
            check(System.currentTimeMillis() < deadline) { "The server never noticed the edit to $path" }
            Thread.sleep(100)
        }
    }

    /** Syncs the devices in turn until a whole round moves nothing, then says they and the disk agree. */
    private suspend fun settle(vararg devices: Device): Map<String, String> {
        repeat(12) {
            val moved = devices.map { it.sync() }.any { it.changedAnything || it.merged.isNotEmpty() || it.conflicted.isNotEmpty() || it.failed.isNotEmpty() }
            if (!moved) {
                val disk = onDisk()
                for (device in devices) assertEquals(disk, device.notes(), "${device.name} and the server's folder do not hold the same notes")
                return disk
            }
        }
        error("The devices never settled")
    }

    private fun withServer(block: suspend () -> Unit) {
        if (command == null) return
        runBlocking { withTimeout(300.seconds) { block() } }
    }

    private suspend fun twoDevices(): Pair<Device, Device> {
        val a = Device("Made-up phone A").apply { pair() }
        val b = Device("Made-up phone B").apply { pair() }
        return a to b
    }

    @Test
    fun aFirstSyncBringsEveryNoteAndASecondBringsNone() = withServer {
        repeat(12) { write("note-$it.org", "* TODO Made-up note $it\n") }
        write("projects/work.org", "#+title: Work\n* Meeting :made:up:\n")
        write("café ☕/été #1?.org", "* A name that needs encoding\n")
        write(".stversions/old.org", "* not a note\n")
        val (a, b) = twoDevices()

        val first = a.sync()
        assertEquals(14, first.pulled.size, first.toString())
        assertTrue(first.failed.isEmpty(), first.toString())
        val again = a.sync()
        assertTrue(!again.changedAnything, again.toString())
        assertEquals(14, settle(a, b).size)
    }

    @Test
    fun anEditOnOneDeviceReachesTheOtherThroughTheChangeStream() = withServer {
        write("inbox.org", "* TODO Buy made-up milk\n")
        val (a, b) = twoDevices()
        settle(a, b)
        b.watch()
        delay(1_000)

        a.store.save("inbox.org", "* DONE Buy made-up milk\n")
        val report = a.sync()
        assertEquals(listOf("inbox.org"), report.pushed)
        assertEquals("* DONE Buy made-up milk\n", File(notes, "inbox.org").readText())

        b.awaitApplied("inbox.org")
        assertEquals("* DONE Buy made-up milk\n", b.store.read("inbox.org"))
    }

    @Test
    fun anEditMadeOnTheComputerReachesBothDevicesThroughTheChangeStream() = withServer {
        write("inbox.org", "* TODO one\n")
        val (a, b) = twoDevices()
        settle(a, b)
        a.watch()
        b.watch()
        delay(1_000)

        write("inbox.org", "* TODO one\n* TODO two, typed in an editor\n")
        a.awaitApplied("inbox.org")
        b.awaitApplied("inbox.org")
        write("new-folder/made-in-an-editor.org", "* a new note\n")
        a.awaitApplied("new-folder/made-in-an-editor.org")
        b.awaitApplied("new-folder/made-in-an-editor.org")
        File(notes, "inbox.org").delete()
        a.awaitApplied("inbox.org")
        assertEquals(null, a.store.read("inbox.org"))
        assertEquals(setOf("new-folder/made-in-an-editor.org"), settle(a, b).keys)
    }

    @Test
    fun editsUnderDifferentHeadingsAreMerged() = withServer {
        write("plan.org", "* One\nfirst\n* Two\nsecond\n* Three\nthird\n")
        val (a, b) = twoDevices()
        settle(a, b)

        a.store.save("plan.org", "* One\nfirst, edited on A\n* Two\nsecond\n* Three\nthird\n")
        b.store.save("plan.org", "* One\nfirst\n* Two\nsecond\n* Three\nthird, edited on B\n")
        assertEquals(listOf("plan.org"), a.sync().pushed)
        val merged = b.sync()
        assertEquals(listOf("plan.org"), merged.merged, merged.toString())

        val disk = settle(a, b)
        assertEquals("* One\nfirst, edited on A\n* Two\nsecond\n* Three\nthird, edited on B\n", disk["plan.org"])
        assertEquals(1, disk.size, "no conflict copy was made")
    }

    @Test
    fun editsToTheSameLineLeaveTheServersVersionAndAConflictCopy() = withServer {
        write("plan.org", "* One\nfirst\n")
        val (a, b) = twoDevices()
        settle(a, b)

        a.store.save("plan.org", "* One\nfirst, as A has it\n")
        b.store.save("plan.org", "* One\nfirst, as B has it\n")
        a.sync()
        val conflicted = b.sync()
        assertEquals(listOf("plan.org"), conflicted.conflicted, conflicted.toString())

        val disk = settle(a, b)
        assertEquals("* One\nfirst, as A has it\n", disk["plan.org"], "the server's version keeps the path")
        val copy = disk.keys.single { it != "plan.org" }
        assertTrue(copy.startsWith("plan.sync-conflict-"), copy)
        assertEquals("* One\nfirst, as B has it\n", disk[copy])
    }

    @Test
    fun anEditMadeOnTheComputerWhileTheDeviceTypedIsNotOverwritten() = withServer {
        write("plan.org", "* One\nfirst\n* Two\nsecond\n")
        val (a, b) = twoDevices()
        settle(a, b)

        a.store.save("plan.org", "* One\nfirst, edited on A\n* Two\nsecond\n")
        write("plan.org", "* One\nfirst\n* Two\nsecond, edited in an editor\n")
        val report = a.sync()
        assertEquals(listOf("plan.org"), report.merged, report.toString())
        assertEquals("* One\nfirst, edited on A\n* Two\nsecond, edited in an editor\n", settle(a, b)["plan.org"])
    }

    @Test
    fun aDeletionGoesToTheServersTrashAndFromTheOtherDevice() = withServer {
        write("keep.org", "* keep\n")
        write("gone.org", "* gone\n")
        val (a, b) = twoDevices()
        settle(a, b)

        a.store.delete("gone.org")
        a.sync()
        assertTrue(!File(notes, "gone.org").exists(), "the deletion reached the server")
        assertEquals(setOf("keep.org"), settle(a, b).keys)
        val trashed = File(notes, ".korgnote-trash").walkTopDown().filter { it.isFile }.toList()
        assertEquals(listOf("* gone\n"), trashed.map { it.readText() }, "the note is recoverable on the computer")
    }

    @Test
    fun aDeletionDoesNotTakeAnEditMadeElsewhere() = withServer {
        write("note.org", "* note\n")
        val (a, b) = twoDevices()
        settle(a, b)

        a.store.delete("note.org")
        b.store.save("note.org", "* note, edited on B\n")
        b.sync()
        a.sync()
        assertEquals(mapOf("note.org" to "* note, edited on B\n"), settle(a, b), "the edit wins over the deletion")
    }

    @Test
    fun aRenameIsSeenAsOneNoteUnderItsNewName() = withServer {
        write("old name.org", "* renamed\n")
        val (a, b) = twoDevices()
        settle(a, b)

        assertTrue(a.store.rename("old name.org", "folder/new name é.org"))
        a.sync()
        assertEquals(mapOf("folder/new name é.org" to "* renamed\n"), settle(a, b))
    }

    @Test
    fun aNoteMadeAtTheSamePathOnTwoDevicesIsKeptTwice() = withServer {
        val (a, b) = twoDevices()
        settle(a, b)

        assertTrue(a.store.create("capture.org", "* captured on A\n"))
        assertTrue(b.store.create("capture.org", "* captured on B\n"))
        a.sync()
        val report = b.sync()
        assertEquals(listOf("capture.org"), report.conflicted, report.toString())

        val disk = settle(a, b)
        assertEquals("* captured on A\n", disk["capture.org"])
        assertEquals(listOf("* captured on B\n"), disk.filterKeys { it != "capture.org" }.values.toList())
    }

    @Test
    fun aServerStartedAgainIsCaughtUpWithFromAWholeManifest() = withServer {
        repeat(3) { write("note-$it.org", "* note $it\n") }
        val (a, b) = twoDevices()
        settle(a, b)
        val before = a.store.generation()

        a.store.save("note-0.org", "* note 0, edited while the server was away\n")
        stop()
        write("note-1.org", "* note 1, edited on the computer while the server was away\n")
        File(notes, "note-2.org").delete()
        start()

        val disk = settle(a, b)
        assertEquals("* note 0, edited while the server was away\n", disk["note-0.org"])
        assertEquals("* note 1, edited on the computer while the server was away\n", disk["note-1.org"])
        assertEquals(setOf("note-0.org", "note-1.org"), disk.keys)
        assertTrue(a.store.generation() != before, "the generation is the new server's")
    }

    @Test
    fun aRevokedDeviceIsToldItIsNotPairedAndPairsAgainAsItself() = withServer {
        write("note.org", "* note\n")
        val a = Device("Made-up phone A").apply { pair() }
        a.sync()
        val id = assertNotNull(a.deviceId)
        assertEquals(302, runCatching { localNoRedirect("/devices/$id/revoke") }.getOrDefault(-1))

        val refused = runCatching { a.sync() }.exceptionOrNull()
        assertTrue(refused is NotPairedException, "was $refused")

        a.pair()
        assertTrue(a.deviceId != id)
        assertEquals(mapOf("note.org" to "* note\n"), settle(a))
        assertTrue(local("GET", "/devices").second.contains("Made-up phone A"))
    }

    private fun localNoRedirect(path: String): Int {
        val connection = URI("http://127.0.0.1:$httpPort$path").toURL().openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.outputStream.close()
        return connection.responseCode
    }

    /**
     * A made-up day on two phones and the computer, from a seed: edits, new notes, deletions and
     * renames wherever they fall, some while a phone is away. Whatever happens, once everyone has
     * synced the three hold the same notes, and no line anyone typed is gone.
     */
    @Test
    fun aMadeUpDayOnTwoDevicesAndTheComputerEndsWithAllThreeAlike() = withServer {
        for (seed in 1..3) {
            val random = Random(seed)
            val (a, b) = twoDevices()
            val typed = mutableListOf<String>()
            fun line(): String = "- typed line ${typed.size} of day $seed".also { typed += it }
            val names = (0 until 6).map { "day$seed/note-$it.org" }
            names.take(3).forEach { write(it, "* ${it.substringAfterLast('/')}\n${line()}\n") }
            settle(a, b)

            repeat(60) { step ->
                val path = names.random(random)
                when (val what = random.nextInt(10)) {
                    in 0..4 -> {
                        val device = if (random.nextBoolean()) a else b
                        val text = device.store.read(path)
                        if (text == null) device.store.create(path, "* ${path.substringAfterLast('/')}\n${line()}\n")
                        else device.store.save(path, if (random.nextBoolean()) text + line() + "\n" else text.lines().toMutableList().also { it.add(1, line()) }.joinToString("\n"))
                    }
                    5 -> onTheComputer(path) {
                        File(notes, path).takeIf { it.exists() }?.appendText(line() + "\n") ?: write(path, "* ${path.substringAfterLast('/')}\n${line()}\n")
                    }
                    6 -> (if (random.nextBoolean()) a else b).let { device -> if (device.store.read(path) != null) device.store.delete(path) }
                    else -> if (what == 7 || step % 3 == 0) (if (random.nextBoolean()) a else b).sync()
                }
            }
            val disk = settle(a, b)
            // A deletion may take a note's lines with it, but never the lines of an edit that was not yet synced when it was made.
            val kept = disk.values.joinToString("\n")
            val lost = typed.filter { it !in kept }
            assertTrue(lost.size < typed.size, "every line of day $seed is gone")
            // The day's notes are cleared from a device, as a person would, for the next day to start clean.
            disk.keys.forEach { a.store.delete(it) }
            assertTrue(settle(a, b).isEmpty())
        }
    }
}
