package com.korgnote.server

import com.korgnote.common.Greeting
import com.korgnote.common.SERVER_PORT
import com.korgnote.common.fs.TreeUtils.buildFromPaths
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticFiles
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.deflate
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.request.httpMethod
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.request.uri
import io.ktor.server.request.userAgent
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.io.File
import java.nio.file.Paths

fun main() {
    val security = ServerSecurity.fromEnvironment()
    // KORGNOTE_PORT mostly exists so a second instance can be run alongside the real one while
    // testing, without stopping the one being used.
    val port = System.getenv("KORGNOTE_PORT")?.trim()?.toIntOrNull() ?: SERVER_PORT
    embeddedServer(Netty, port = port, host = security.bindHost) { module(security, watchFiles = true) }
        .start(wait = true)
}

/**
 * [security] defaults to open because that is what the test host and a plain local run want; `main`
 * passes what the environment actually asks for.
 */
fun Application.module(
    security: ServerSecurity = ServerSecurity.open(),
    /**
     * Whether to watch the notes directory for external edits.
     *
     * Off by default because tests start this module dozens of times, and a file watcher per test —
     * polling the filesystem on platforms without a native backend — costs far more than the tests
     * themselves. `main` turns it on.
     */
    watchFiles: Boolean = false,
) {
    log.info(security.describe())

    install(ContentNegotiation) { json() }

    // Org files are text and compress well; the whole-library endpoint is over a megabyte.
    install(Compression) {
        gzip()
        deflate()
    }

    if (security.requiresToken) {
        intercept(ApplicationCallPipeline.Plugins) {
            if (call.request.uri.startsWith("/api")) {
                val offered = call.request.header(HttpHeaders.Authorization)
                    ?.removePrefix("Bearer ")
                    ?.trim()
                if (offered != security.token) {
                    call.respondText(
                        "Unauthorized. Send 'Authorization: Bearer <token>'.",
                        status = HttpStatusCode.Unauthorized,
                    )
                    return@intercept finish()
                }
            }
        }
    }

    intercept(ApplicationCallPipeline.Monitoring) {
        val origin = call.request.origin
        val ua = call.request.userAgent()
        this@module.log.info(
            "Request ${call.request.httpMethod.value} ${call.request.uri} " +
                "from ${origin.remoteHost}:${origin.remotePort} UA=$ua"
        )
    }

    // Load .org files from a directory on the file system or resources
    fun resolvePath(path: String): File {
        var expanded = when {
            path.startsWith("~/") -> {
                val home = System.getProperty("user.home")
                path.replaceFirst("~/", home + File.separator)
            }
            path.startsWith("~") -> {
                val home = System.getProperty("user.home")
                path.replaceFirst("~", home)
            }
            else -> path
        }

        // Also handle Windows environment variables if present in the string
        if (expanded.contains("%")) {
            expanded = expanded.replace(Regex("%([^%]+)%")) { match ->
                val varName = match.groupValues[1]
                System.getenv(varName) ?: match.value
            }
        }

        return File(expanded)
    }

    val notesDirOverride = System.getProperty("notes.dir")?.trim().takeUnless { it.isNullOrEmpty() }
        ?: System.getenv("NOTES_DIR")?.trim().takeUnless { it.isNullOrEmpty() }
        ?: "notes" // Default to a 'notes' directory in the current working directory

    /**
     * The notes directory, or the bundled samples when it does not exist.
     *
     * Built once, but it reads the disk on every request rather than caching contents — see
     * [FileStore].
     */
    fun openStore(): FileStore {
        val notesDir = resolvePath(notesDirOverride)
        if (notesDir.exists() && notesDir.isDirectory) {
            log.info("Serving .org files from ${notesDir.absolutePath}")
            return FileStore(notesDir.toPath())
        }
        val samples = this::class.java.classLoader.getResource("org/")
            ?.let { runCatching { File(it.toURI()) }.getOrNull() }
            ?.takeIf { it.exists() && it.isDirectory }
        if (samples != null) {
            log.info("Directory '${notesDir.absolutePath}' not found, serving bundled samples read-only")
            return FileStore(samples.toPath(), readOnly = true)
        }
        log.warn("Directory '${notesDir.absolutePath}' not found and no bundled samples; serving nothing")
        return FileStore(notesDir.toPath(), readOnly = true)
    }

    val store = openStore()

    // The generation counter the sync API hands out, and the watcher that keeps it honest about
    // edits the server did not make itself.
    val journal = ChangeJournal()
    // Bundled samples are read-only and never change, so there is nothing to watch.
    val watcher = if (watchFiles && !store.readOnly) NotesWatcher(store.root, store, journal) else null
    watcher?.start(this)
    monitor.subscribe(ApplicationStopping) { watcher?.stop() }

    /**
     * The version of each file this server last handed out, used to refuse a save that would
     * overwrite a change the client never saw — an edit made in Emacs, or arriving via Syncthing.
     *
     * A client that sends `If-Match` is checked against that instead. This is deliberately not a
     * full concurrency protocol: two app clients editing the same file still race, which is D9 and
     * waits for the sync work.
     */
    val lastServed = java.util.concurrent.ConcurrentHashMap<String, String>()

    routing {
        // Robust resolver for the WASM app output directory (Option A)
        fun wasmCandidates(): List<File> {
            val override = System.getProperty("wasm.dir")?.trim().takeUnless { it.isNullOrEmpty() }
                ?: System.getenv("WASM_DIR")?.trim().takeUnless { it.isNullOrEmpty() }

            val paths = mutableListOf<String>()
            if (override != null) paths += override

            // Project-root relative (works if working dir is repo root)
            paths += listOf(
                "composeApp/build/dist/wasmJs/productionExecutable",
                "composeApp/build/dist/wasmJs/developmentExecutable"
            )
            // Server-module relative (works if working dir is repoRoot/server)
            paths += listOf(
                "../composeApp/build/dist/wasmJs/productionExecutable",
                "../composeApp/build/dist/wasmJs/developmentExecutable"
            )

            return paths.map { Paths.get(it).toFile() }
        }

        val wasmOut = wasmCandidates().firstOrNull { it.exists() }
        println(
            "Working dir: ${System.getProperty("user.dir")} | Using WASM dir: ${wasmOut?.absolutePath ?: "<not found>"}"
        )

        if (wasmOut != null) {
            staticFiles("/", wasmOut, index = "index.html")
        }

        get("/favicon.png") {
            respondFavIcon()
        }
        get("/favicon.ico") {
            respondFavIcon()
        }

        // API endpoints kept under /api to avoid collision with index.html routing
        // Serve index.html at root when WASM output exists; otherwise fall back to legacy greeting
        if (wasmOut == null) {
            get("/") {
                call.respondText("Ktor: ${Greeting().greet()}")
            }
        }

        route("/api") {
            // Sync API v1. The endpoints it replaced were deleted in T10.
            syncApiV1(store, journal)

            get("/") {
                call.respondText("Ktor: ${Greeting().greet()}")
            }
        }
    }
}
suspend fun RoutingContext.respondFavIcon() {
    val bytes = this::class.java.classLoader.getResourceAsStream("favicon.png")?.readBytes()
    if (bytes == null) {
        call.respondText("Not found", status = HttpStatusCode.NotFound)
    } else {
        call.respondBytes(bytes, contentType = io.ktor.http.ContentType.Image.PNG)
    }
}