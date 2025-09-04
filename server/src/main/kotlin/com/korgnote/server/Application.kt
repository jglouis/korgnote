package com.korgnote.server

import com.korgnote.common.Greeting
import com.korgnote.common.SERVER_PORT
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.File

fun main() {
    embeddedServer(Netty, port = SERVER_PORT, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    install(ContentNegotiation) { json() }

    // CORS for browser (Wasm/JS) client during development
    install(CORS) {
        // Allow the browser app origins
        allowHost("localhost", schemes = listOf("http"))
        allowHost("192.168.129.36", schemes = listOf("http"))

        // Methods/Headers commonly used by our client
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Options) // preflight
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Accept)

        allowCredentials = false
        allowNonSimpleContentTypes = true
        allowSameOrigin = false
    }

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
        // Serve the WASM app statically from the composeApp build output if present.
        // Build it first with: ./gradlew :composeApp:wasmJsBrowserProductionWebpack OR productionExecutable
        val wasmProd = File("composeApp/build/dist/wasmJs/productionExecutable")
        val wasmDev = File("composeApp/build/dist/wasmJs/developmentExecutable")
        val wasmOut = when {
            wasmProd.exists() -> wasmProd
            wasmDev.exists() -> wasmDev
            else -> null
        }
        if (wasmOut != null) {
            staticFiles("/", wasmOut, index = "index.html")
        }

        // API endpoints kept under /api to avoid collision with index.html routing
        // Keep legacy root endpoint for tests
        get("/") {
            call.respondText("Ktor: ${Greeting().greet()}")
        }

        route("/api") {
            get("/") {
                call.respondText("Ktor: ${Greeting().greet()}")
            }
            // Returns a JSON object mapping filename -> org content
            get("/org-contents") {
                call.respond(orgContents)
            }
            // Returns a JSON array of file paths (strings) representing the tree
            get("/org-tree-paths") {
                call.respond(orgTreePaths)
            }
            // Convenience endpoint to fetch a single file content
            get("/content/{name}") {
                val name = call.parameters["name"]
                val body = orgContents[name]
                if (body == null) call.respondText("Not found", status = io.ktor.http.HttpStatusCode.NotFound)
                else call.respondText(body)
            }
        }
    }
}