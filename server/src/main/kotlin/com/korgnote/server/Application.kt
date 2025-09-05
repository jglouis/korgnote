package com.korgnote.server

import com.korgnote.common.Greeting
import com.korgnote.common.SERVER_PORT
import com.korgnote.common.fs.TreeUtils.buildFromPaths
import com.korgnote.common.org.OrgDocument
import com.korgnote.common.org.toOrgDocument
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticFiles
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
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

    // Load .org examples from resources under /org directory
    fun loadOrgResources(): Map<String, String> {
        val cl = this::class.java.classLoader
        // Try to list files in resources/org via the file system (works in tests and from Gradle run)
        val baseUrl = cl.getResource("org/")
        val map = linkedMapOf<String, String>()
        if (baseUrl != null) {
            val file = kotlin.runCatching { File(baseUrl.toURI()) }.getOrNull()
            if (file != null && file.exists() && file.isDirectory) {
                file.walkTopDown().filter { it.isFile && it.name.endsWith(".org") }.forEach { f ->
                    map[f.name] = f.readText()
                }
            }
        }
        return map
    }

    val orgContents: Map<String, String> = loadOrgResources()

    // Derive tree paths from the available .org resources (or fallback contents)
    fun deriveOrgTreePaths(fileNames: Set<String>): List<String> {
        fun isJournal(name: String): Boolean = Regex("^(\\d{4})-(\\d{2})-(\\d{2})\\.org$").matches(name)
        fun journalPath(name: String): String {
            val year = name.take(4)
            return "Org/Journal/$year/$name"
        }
        val agenda = setOf("inbox.org", "next-actions.org", "calendar.org", "sep-2025.org")
        val projects = setOf("korgnote.org", "website-redesign.org", "research-notes.org", "learn_java_by_its_pitfalls.org", "voidfall.org")
        val reference = setOf("books.org", "quotes.org")
        return fileNames.mapNotNull { fn ->
            when {
                isJournal(fn) -> journalPath(fn)
                fn in agenda -> "Org/Agenda/$fn"
                fn in projects -> "Org/Projects/$fn"
                fn in reference -> "Org/Reference/$fn"
                fn == "notes.org" -> fn
                else -> null // omit unknowns from tree to avoid broken links
            }
        }.sorted()
    }
    val orgTreePaths: List<String> = deriveOrgTreePaths(orgContents.keys)

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
                val tree = buildFromPaths(orgTreePaths, rootName = "Documents")
                call.respond(tree)
            }
            // Convenience endpoint to fetch a single file content
            get("/content/{name}") {
                val name = call.parameters["name"]
                val body = orgContents[name]
                if (body == null) call.respondText("Not found", status = HttpStatusCode.NotFound)
                else call.respondText(body)
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