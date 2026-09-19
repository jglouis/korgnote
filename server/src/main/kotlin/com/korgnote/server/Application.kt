package com.korgnote.server

import com.korgnote.common.Greeting
import com.korgnote.common.SERVER_PORT
import com.korgnote.common.fs.TreeUtils.buildFromPaths
import com.korgnote.common.org.OrgDocument
import com.korgnote.common.org.toOrgDocument
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
import io.ktor.server.request.receiveText
import io.ktor.server.request.uri
import io.ktor.server.request.userAgent
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

    fun loadOrgFiles(): Map<String, String> {
        val map = linkedMapOf<String, String>()

        val notesDir = resolvePath(notesDirOverride)
        if (notesDir.exists() && notesDir.isDirectory) {
            println("Loading .org files from directory: ${notesDir.absolutePath}")
            notesDir.walkTopDown()
                .filter { it.isFile && it.extension == "org" }
                .forEach { f ->
                    val relativePath = f.relativeTo(notesDir).path.replace(File.separatorChar, '/')
                    map[relativePath] = f.readText()
                }
        } else {
            // Fallback to resources for examples
            println("Directory '${notesDir.absolutePath}' not found, falling back to resources/org")
            val cl = this::class.java.classLoader
            val baseUrl = cl.getResource("org/")
            if (baseUrl != null) {
                val file = kotlin.runCatching { File(baseUrl.toURI()) }.getOrNull()
                if (file != null && file.exists() && file.isDirectory) {
                    file.walkTopDown().filter { it.isFile && it.name.endsWith(".org") }.forEach { f ->
                        // In resources we assume flat structure for now or match the walk
                        val relativePath = f.relativeTo(file).path.replace(File.separatorChar, '/')
                        map[relativePath] = f.readText()
                    }
                }
            }
        }
        return map
    }

    val orgContents: Map<String, String> = loadOrgFiles()

    // Use relative paths as they are already provided by loadOrgFiles keys
    val orgTreePaths: List<String> = orgContents.keys.sorted()

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
                val parsed: Map<String, OrgDocument> = orgContents.mapValues { (_, text) ->
                    text.toOrgDocument()
                }
                call.respond(parsed)
            }
            // Returns a JSON serializable Folder tree instead of raw paths
            get("/org-tree-paths") {
                val tree = buildFromPaths(orgTreePaths, rootName = "Org")
                call.respond(tree)
            }
            // Convenience endpoint to fetch a single file content
            get("/content/{name...}") {
                val name = call.parameters.getAll("name")?.joinToString("/")
                val body = orgContents[name]
                if (body == null) call.respondText("Not found: $name", status = HttpStatusCode.NotFound)
                else call.respondText(body)
            }

            // Save content for a single file
            post("/content/{name...}") {
                val name = call.parameters.getAll("name")?.joinToString("/") ?: ""
                val newBody = call.receiveText()

                // Check if the file exists in our current loaded set
                if (!orgContents.containsKey(name)) {
                    call.respondText("File not found or not in allowed directory", status = HttpStatusCode.NotFound)
                    return@post
                }

                // Identify the file on disk
                val notesDir = resolvePath(notesDirOverride)
                if (notesDir.exists() && notesDir.isDirectory) {
                    val targetFile = File(notesDir, name.replace('/', File.separatorChar))
                    if (targetFile.exists() && targetFile.isFile && targetFile.extension == "org") {
                        try {
                            targetFile.writeText(newBody)
                            // Update our in-memory cache
                            (orgContents as MutableMap)[name] = newBody
                            call.respond(HttpStatusCode.OK, "Saved")
                        } catch (e: Exception) {
                            call.respondText("Failed to write: ${e.message}", status = HttpStatusCode.InternalServerError)
                        }
                    } else {
                        call.respondText("Target file not found on disk", status = HttpStatusCode.NotFound)
                    }
                } else {
                    call.respondText("Server is in read-only mode (resources)", status = HttpStatusCode.Forbidden)
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