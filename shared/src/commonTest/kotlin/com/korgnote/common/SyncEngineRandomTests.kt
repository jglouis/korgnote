package com.korgnote.common

import com.korgnote.common.replica.DeleteResult
import com.korgnote.common.replica.DocumentStore
import com.korgnote.common.replica.InMemoryReplicaStorage
import com.korgnote.common.replica.PushResult
import com.korgnote.common.replica.RemoteEntry
import com.korgnote.common.replica.RemoteFile
import com.korgnote.common.replica.RemoteManifest
import com.korgnote.common.replica.SyncApi
import com.korgnote.common.replica.SyncEngine
import com.korgnote.common.sync.conflictCopyOriginal
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.fail

/**
 * Two devices and a server, doing random things to the same few notes, with requests failing on the
 * way — some after the server acted on them, as when a response is lost. Then everything syncs until
 * it is quiet, and three things must hold:
 *
 * - **Converged**: both replicas hold exactly what the server holds, with nothing left to send.
 * - **Nothing lost**: every line anyone wrote is still somewhere, unless a device took it out on
 *   purpose — by editing it away, deleting its note, or settling a conflict without it.
 * - **Nothing spliced**: no note holds two notes' headers, as merging two unrelated notes would.
 *
 * Each note is an `:ID:` line and lines `t<n>`, every one unique, so that "still somewhere" can be
 * checked exactly. A failure names its seed and the steps that led there; [SEEDS] runs them all.
 */
class SyncEngineRandomTests {

    @Test
    fun twoDevicesDoingRandomThingsConvergeAndLoseNothing() = runTest {
        val failures = mutableListOf<String>()
        for (seed in 0 until SEEDS) {
            val problem = Session(seed).run()
            if (problem != null) failures += problem
            if (failures.size >= 3) break
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n\n"))
    }

    private class Device(val name: String, val store: DocumentStore, val engine: SyncEngine)

    private class Session(val seed: Int, val steps: Int = STEPS) {
        val random = Random(seed)
        val server = FlakyServer(random)
        val log = mutableListOf<String>()
        private var nextToken = 0
        private var nextId = 0
        private var stamp = 0

        /** Every token ever written, and those a device removed on purpose. */
        val written = mutableSetOf<String>()
        val retired = mutableSetOf<String>()

        val devices = listOf("A", "B").map { name ->
            val store = DocumentStore(InMemoryReplicaStorage())
            Device(name, store, SyncEngine(store, server) { "20260921-${(100000 + stamp++).toString().takeLast(6)}" })
        }

        fun token() = "t${nextToken++}"
        fun header() = ":ID: n${nextId++}"

        /** Null when everything held; otherwise what broke, the seed, and the steps. */
        suspend fun run(): String? {
            repeat(2) { server.put("n$it.org", "${header()}\n${token()}\n${token()}\n") }
            written += server.files.values.flatMap { it.second.lines() }.filter { it.isNotEmpty() }
            for (device in devices) device.engine.sync()

            server.faults = true
            repeat(steps) { step(devices[random.nextInt(devices.size)]) }
            server.faults = false

            settle()?.let { return report(it) }
            return check()?.let(::report)
        }

        private fun report(problem: String) =
            "seed $seed: $problem\n  steps:\n" + log.takeLast(100).joinToString("\n") { "    $it" }

        private suspend fun step(device: Device) {
            val store = device.store
            val before = tokensByNote(store)
            val roll = random.nextInt(100)
            val action = when {
                roll < 30 -> edit(store)
                roll < 38 -> create(store)
                roll < 43 -> delete(store)
                roll < 47 -> rename(store)
                roll < 53 -> resolve(store)
                roll < 63 -> pull(device)
                else -> sync(device)
            }
            // What the action itself added or took out; a sync or pull only moves what others wrote.
            // Taken out of any one note counts, though another may still hold it: during a rename
            // a device may briefly hold both names, and the stale one goes when the rename arrives.
            val after = tokensByNote(store)
            if (!action.startsWith("sync") && !action.startsWith("pull")) {
                written += after.values.flatten() - before.values.flatten().toSet()
                for ((path, tokens) in before) retired += tokens - after[path].orEmpty()
            }
            log += "${device.name}: $action"
        }

        private suspend fun notes(store: DocumentStore) = store.paths().sorted()

        private suspend fun edit(store: DocumentStore): String {
            val path = notes(store).randomOrNull(random) ?: return "edit: nothing to edit"
            val lines = store.read(path)!!.trimEnd('\n').split('\n').toMutableList()
            // The first line is the note's header; edits go below it.
            val described = when (random.nextInt(3)) {
                0 -> token().let { lines.add(1 + random.nextInt(lines.size), it); "add $it" }
                1 -> if (lines.size > 1) {
                    val at = 1 + random.nextInt(lines.size - 1)
                    val old = lines[at]
                    lines[at] = token()
                    "replace $old with ${lines[at]}"
                } else return "edit $path: nothing to replace"
                else -> if (lines.size > 1) "remove ${lines.removeAt(1 + random.nextInt(lines.size - 1))}" else return "edit $path: nothing to remove"
            }
            store.save(path, lines.joinToString("\n", postfix = "\n"))
            return "edit $path: $described"
        }

        private suspend fun create(store: DocumentStore): String {
            val path = POOL.random(random)
            val content = "${header()}\n${token()}\n"
            return if (store.create(path, content)) "create $path: ${content.lines().first()}" else "create $path: taken"
        }

        private suspend fun delete(store: DocumentStore): String {
            val path = notes(store).randomOrNull(random) ?: return "delete: nothing to delete"
            store.delete(path)
            return "delete $path"
        }

        private suspend fun rename(store: DocumentStore): String {
            val from = notes(store).randomOrNull(random) ?: return "rename: nothing to rename"
            val to = POOL.random(random)
            return if (store.rename(from, to)) "rename $from to $to" else "rename $from to $to: refused"
        }

        /** Settles a conflict the way the app's banner does: keep the note, or use the copy. */
        private suspend fun resolve(store: DocumentStore): String {
            val copy = notes(store).filter { conflictCopyOriginal(it) != null }.randomOrNull(random)
                ?: return "resolve: no conflict"
            val note = conflictCopyOriginal(copy)!!
            if (store.read(note) == null) return "resolve $copy: its note is gone"
            return if (random.nextBoolean()) {
                store.delete(copy)
                "resolve $copy: keep the note"
            } else {
                store.replaceWithCopy(note, copy)
                "resolve $copy: use the copy"
            }
        }

        /** What the change stream does on an event: one file, from the server. */
        private suspend fun pull(device: Device): String {
            val path = server.files.keys.randomOrNull(random) ?: return "pull: server empty"
            return try {
                device.engine.pull(path)
                "pull $path"
            } catch (e: RuntimeException) {
                "pull $path: ${e.message}"
            }
        }

        private suspend fun sync(device: Device): String = try {
            val report = device.engine.sync()
            "sync: pushed ${report.pushed}, pulled ${report.pulled}, conflicted ${report.conflicted}"
        } catch (e: RuntimeException) {
            "sync: ${e.message}"
        }

        /** Syncs both devices, faults off, until a whole round changes nothing. */
        private suspend fun settle(): String? {
            var last: String? = null
            repeat(12) {
                for (device in devices) log += "${device.name}: ${sync(device)} (settling)"
                val now = snapshot()
                if (now == last && devices.all { it.store.dirtyPaths().isEmpty() && it.store.tombstones().isEmpty() }) {
                    return null
                }
                last = now
            }
            return "never settled"
        }

        private suspend fun snapshot(): String =
            server.files.inOrder() + devices.map { contents(it.store).inOrder() }

        private suspend fun check(): String? {
            val onServer = server.files.mapValues { it.value.second }
            for (device in devices) {
                val here = contents(device.store)
                if (here != onServer) {
                    return "${device.name} did not converge:\n  server ${onServer.inOrder()}\n  ${device.name}     ${here.inOrder()}"
                }
            }
            val spliced = onServer.filterValues { text -> text.lines().count { it.startsWith(":ID:") } > 1 }
            if (spliced.isNotEmpty()) return "spliced: ${spliced.inOrder()}"
            val surviving = onServer.values.flatMap { it.lines() }.toSet()
            val lost = written - retired - surviving
            if (lost.isNotEmpty()) return "lost ${lost.sorted()}, written by nobody's choice out of: ${onServer.inOrder()}"
            return null
        }

        private suspend fun contents(store: DocumentStore) = store.paths().associateWith { store.read(it)!! }

        private suspend fun tokensByNote(store: DocumentStore) =
            store.paths().associateWith { path -> store.read(path)!!.lines().filter { it.isNotEmpty() }.toSet() }
    }

    /**
     * The v1 API as the real server answers it — etags from content, a journal for deltas, writes
     * only over the version named — and a network that drops requests: before the server acts, or
     * after, the answer lost on the way back.
     */
    private class FlakyServer(val random: Random) : SyncApi {
        val files = mutableMapOf<String, Pair<String, String>>()
        private val journal = mutableListOf<Pair<Long, String>>()
        private var generation = 0L
        var faults = false

        fun put(path: String, text: String) {
            files[path] = etagOf(text) to text
            journal += ++generation to path
        }

        private fun remove(path: String) {
            files.remove(path)
            journal += ++generation to path
        }

        private fun etagOf(text: String) = "e${text.hashCode().toUInt().toString(16)}-${text.length}"

        private fun beforeActing() {
            if (faults && random.nextInt(100) < 10) throw RuntimeException("connection refused")
        }

        private fun afterActing() {
            if (faults && random.nextInt(100) < 5) throw RuntimeException("response lost")
        }

        override suspend fun manifest(since: Long?): RemoteManifest {
            beforeActing()
            // Now and then the journal "restarted", as the real server's does, and the answer is whole.
            if (since == null || random.nextInt(10) == 0) {
                return RemoteManifest(generation, complete = true, entries = files.map { (path, v) -> RemoteEntry(path, v.first) })
            }
            val moved = journal.filter { it.first > since }.map { it.second }.toSet()
            return RemoteManifest(
                generation,
                complete = false,
                entries = moved.mapNotNull { path -> files[path]?.let { RemoteEntry(path, it.first) } },
                deleted = moved.filter { it !in files },
            )
        }

        override suspend fun fetch(path: String): RemoteFile? {
            beforeActing()
            return files[path]?.let { RemoteFile(path, it.first, it.second) }
        }

        override suspend fun bundle(since: Long?): List<RemoteFile>? {
            beforeActing()
            val moved = since?.let { s -> journal.filter { it.first > s }.map { it.second }.toSet() }
            return files.filterKeys { moved == null || it in moved }.map { (path, v) -> RemoteFile(path, v.first, v.second) }
        }

        override suspend fun push(path: String, content: String, ifMatch: String?, mustNotExist: Boolean): PushResult {
            beforeActing()
            val current = files[path]
            if (mustNotExist && current != null) return PushResult.Conflict(current.first)
            if (ifMatch != null && current?.first != ifMatch) return PushResult.Conflict(current?.first)
            put(path, content)
            afterActing()
            return PushResult.Pushed(files.getValue(path).first)
        }

        override suspend fun delete(path: String, ifMatch: String): DeleteResult {
            beforeActing()
            val current = files[path] ?: return DeleteResult.Gone
            if (current.first != ifMatch) return DeleteResult.Conflict(current.first)
            remove(path)
            afterActing()
            return DeleteResult.Deleted
        }
    }

    private companion object {
        /** A map as text, keys in order, for comparing states and for failure messages. */
        fun Map<String, *>.inOrder(): String = toList().sortedBy { it.first }.toString()

        const val SEEDS = 400
        const val STEPS = 60
        /** Few names, so that creating, renaming and deleting keep landing on each other. */
        val POOL = listOf("n0.org", "n1.org", "n2.org", "n3.org", "daily.org")
    }
}
