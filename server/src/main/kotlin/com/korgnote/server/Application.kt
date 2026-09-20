package com.korgnote.server

import com.korgnote.common.Greeting
import com.korgnote.common.SERVER_PORT
import com.korgnote.common.fs.TreeUtils.buildFromPaths
import com.korgnote.common.org.OrgDocument
import com.korgnote.common.org.toOrgDocument
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticFiles
import io.ktor.server.netty.Netty
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
    embeddedServer(Netty, port = SERVER_PORT, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    install(ContentNegotiation) { json() }

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

    /**
     * The version of each file this server last handed out, used to refuse a save that would
     * overwrite a change the client never saw — an edit made in Emacs, or arriving via Syncthing.
     *
     * A client that sends `If-Match` is checked against that instead. This is deliberately not a
     * full concurrency protocol: two app clients editing the same file still race, which is D9 and
     * waits for the sync work.
     */
    val lastServed = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Parsed documents, keyed by the content hash they were parsed from. */
    val parsedByEtag = java.util.concurrent.ConcurrentHashMap<String, Pair<String, OrgDocument>>()

    fun parsedDocument(meta: FileMeta): OrgDocument? {
        parsedByEtag[meta.path]?.let { (etag, document) -> if (etag == meta.etag) return document }
        val text = store.read(meta.path) ?: return null
        val document = text.toOrgDocument()
        parsedByEtag[meta.path] = meta.etag to document
        return document
    }

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
            get("/") {
                call.respondText("Ktor: ${Greeting().greet()}")
            }
            // Returns a JSON object mapping filename -> parsed OrgDocument
            get("/org-content") {
                val parsed = buildMap {
                    for (meta in store.list()) {
                        val document = parsedDocument(meta) ?: continue
                        lastServed[meta.path] = meta.etag
                        put(meta.path, document)
                    }
                }
                call.respond(parsed)
            }
            // Returns a JSON serializable Folder tree instead of raw paths
            get("/org-tree-paths") {
                call.respond(buildFromPaths(store.list().map { it.path }, rootName = "Org"))
            }
            // Convenience endpoint to fetch a single file content
            get("/content/{name...}") {
                val name = call.parameters.getAll("name")?.joinToString("/").orEmpty()
                val meta = store.meta(name)
                val body = meta?.let { store.read(name) }
                if (meta == null || body == null) {
                    call.respondText("Not found: $name", status = HttpStatusCode.NotFound)
                } else {
                    lastServed[name] = meta.etag
                    call.response.header(HttpHeaders.ETag, meta.etag)
                    call.respondText(body)
                }
            }

            // Save content for a single file
            post("/content/{name...}") {
                val name = call.parameters.getAll("name")?.joinToString("/").orEmpty()
                val newBody = call.receiveText()
                // An explicit If-Match wins; otherwise fall back to whatever this server last
                // handed out for the file, which catches the edit-in-Emacs case.
                val expected = call.request.header(HttpHeaders.IfMatch)?.trim('"') ?: lastServed[name]

                when (val result = store.write(name, newBody, expected)) {
                    is WriteResult.Written -> {
                        lastServed[name] = result.meta.etag
                        call.response.header(HttpHeaders.ETag, result.meta.etag)
                        call.respond(HttpStatusCode.OK, "Saved")
                    }

                    is WriteResult.Conflict -> {
                        lastServed[name] = result.current.etag
                        call.response.header(HttpHeaders.ETag, result.current.etag)
                        call.respondText(
                            "The file changed on disk since it was read; nothing was written.",
                            status = HttpStatusCode.Conflict,
                        )
                    }

                    WriteResult.NotFound ->
                        call.respondText("File not found or not in allowed directory", status = HttpStatusCode.NotFound)

                    WriteResult.ReadOnly ->
                        call.respondText("Server is in read-only mode (resources)", status = HttpStatusCode.Forbidden)

                    is WriteResult.Rejected ->
                        call.respondText("Cannot write: ${result.reason}", status = HttpStatusCode.UnprocessableEntity)
                }
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