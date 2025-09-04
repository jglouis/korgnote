package com.korgnote.server

import com.korgnote.common.Greeting
import com.korgnote.common.SERVER_PORT
import com.korgnote.common.fs.TreeUtils.buildFromPaths
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticFiles
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
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

    // Fake in-memory org contents; this mirrors the app's previous local data
    val orgContents: Map<String, String> = mapOf(
        "inbox.org" to "* Inbox\n** TODO Capture an idea\nAdded: [2025-09-03 Wed 15:03]",
        "next-actions.org" to "* Next Actions\n** TODO Implement Org preview in app\nSCHEDULED: <2025-09-04 Thu>\n** TODO Review release notes\nSCHEDULED: <2025-09-21 Sun>",
        "calendar.org" to "* Calendar\n** 2025\n*** Team sync\nSCHEDULED: <2025-09-10 Wed>\n*** Release planning\nSCHEDULED: <2025-09-15 Mon>",
        "sep-2025.org" to "* September 2025\n** TODO Kickoff Q4 planning\nSCHEDULED: <2025-09-10 Wed>\n** DONE Submit budget proposal\nSCHEDULED: <2025-09-15 Mon>\n** TODO Write blog post\nSCHEDULED: <2025-09-21 Sun>\n** TODO End of month review\nSCHEDULED: <2025-09-30 Tue>",
        "korgnote.org" to "* Project KorgNote\n** Goals\n- Cross-platform Org viewer\n** Tasks\n- [ ] Show content on select\n** TODO Calendar markers\nSCHEDULED: <2025-09-18 Thu>",
        "website-redesign.org" to "* Website Redesign\n** Milestones\n- [ ] Wireframes\n- [ ] Prototype\n- [ ] Launch",
        "research-notes.org" to "* Research Notes\n- Compose Multiplatform\n- Org-mode parsing",
        "2024-12-31.org" to "* Journal 2024-12-31\n- Reflect on the year",
        "2025-01-01.org" to "* Journal 2025-01-01\n- New beginnings",
        "2025-09-03.org" to "* Journal 2025-09-03\n- Implemented Org content in app",
        "books.org" to "* Books\n** Reading\n- The Pragmatic Programmer\n** Wishlist\n- Structure and Interpretation of Computer Programs",
        "quotes.org" to "* Quotes\n** Alan Kay\n> The best way to predict the future is to invent it.",
        "notes.org" to "* Notes\nMiscellaneous notes"
    )

    // Tree paths representing the hierarchical structure under Documents/
    // These paths map to the filenames present in orgContents
    val orgTreePaths: List<String> = listOf(
        "Org/Agenda/inbox.org",
        "Org/Agenda/next-actions.org",
        "Org/Agenda/calendar.org",
        "Org/Agenda/sep-2025.org",
        "Org/Projects/korgnote.org",
        "Org/Projects/website-redesign.org",
        "Org/Projects/research-notes.org",
        "Org/Journal/2024/2024-12-31.org",
        "Org/Journal/2025/2025-01-01.org",
        "Org/Journal/2025/2025-09-03.org",
        "Org/Reference/books.org",
        "Org/Reference/quotes.org",
        "notes.org"
    )

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
            // Returns a JSON object mapping filename -> org content
            get("/org-contents") {
                call.respond(orgContents)
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