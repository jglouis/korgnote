package com.korgnote.server

import com.korgnote.common.Greeting
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.File
import java.nio.file.Paths

fun main() {
    val config = ServerConfig.fromEnvironment()
    val identity = ServerIdentity.loadOrCreate(config.stateDir)
    val access = ServerAccess(
        DeviceRegistry(File(config.stateDir, "devices.json")),
        securePort = config.httpsPort,
        pairing = PairingDetails(identity.serverId, config.serverName, identity.fingerprint, config.advertisedPort, config.advertisedAddresses),
    )
    fun isSet(name: String) = System.getenv(name)?.trim()?.lowercase() in setOf("1", "true", "yes")
    embeddedServer(
        Netty,
        configure = {
            connector {
                host = config.httpHost
                port = config.httpPort
            }
            sslConnector(
                keyStore = identity.keyStore,
                keyAlias = ServerIdentity.KEY_ALIAS,
                keyStorePassword = { identity.keyStorePassword.toCharArray() },
                privateKeyPassword = { identity.keyStorePassword.toCharArray() },
            ) {
                host = config.httpsHost
                port = config.httpsPort
            }
        },
    ) {
        log.info(config.describe())
        module(
            access,
            watchFiles = true,
            requireNotesDir = isSet(REQUIRE_NOTES_DIR_VARIABLE),
            serveWebClient = !isSet(DISABLE_WEB_VARIABLE),
        )
    }.start(wait = true)
}

/** Serve the API only, even when a web client build is present (the container ships one). */
const val DISABLE_WEB_VARIABLE = "KORGNOTE_DISABLE_WEB"

/**
 * Set by the container image: a missing or unwritable notes directory stops the server instead of
 * falling back to the bundled samples. In a container that fallback would hide a typo in the volume
 * path, or a UID that cannot write to the mount, behind a server that looks healthy.
 */
const val REQUIRE_NOTES_DIR_VARIABLE = "KORGNOTE_REQUIRE_NOTES_DIR"


/**
 * [access] defaults to this computer only, with no network side, because that is what the test host
 * wants; `main` passes the paired devices and the secure port.
 */
fun Application.module(
    access: ServerAccess = ServerAccess.localOnly(),
    /**
     * Whether to watch the notes directory for external edits.
     *
     * Off by default because tests start this module dozens of times, and a file watcher per test —
     * polling the filesystem on platforms without a native backend — costs far more than the tests
     * themselves. `main` turns it on.
     */
    watchFiles: Boolean = false,
    /** Fail instead of falling back to the samples; see [REQUIRE_NOTES_DIR_VARIABLE]. */
    requireNotesDir: Boolean = false,
    /** Serve the wasm client at `/` when a build of it is found; see [DISABLE_WEB_VARIABLE]. */
    serveWebClient: Boolean = true,
) {
    install(ContentNegotiation) { json() }

    // Org files are text and compress well; the whole-library endpoint is over a megabyte.
    install(Compression) {
        gzip()
        deflate()
    }

    intercept(ApplicationCallPipeline.Plugins) {
        val refusal = refusalOf(call, access)
        if (refusal != null) {
            call.respondText(refusal.second, status = refusal.first)
            return@intercept finish()
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
        if (requireNotesDir) {
            check(notesDir.isDirectory) {
                "Notes directory '${notesDir.absolutePath}' does not exist and $REQUIRE_NOTES_DIR_VARIABLE is set. " +
                    "Is the volume mounted?"
            }
            // Write a file rather than ask canWrite(): through Docker Desktop's file sharing a
            // directory can report itself writable and still refuse the write.
            val writable = runCatching {
                File.createTempFile(".korgnote-write-check", null, notesDir).delete()
            }.isSuccess
            check(writable) {
                "Notes directory '${notesDir.absolutePath}' is not writable by this user. " +
                    "Run as the UID/GID that owns it."
            }
        }
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

        val wasmOut = if (serveWebClient) wasmCandidates().firstOrNull { it.exists() } else null
        println(
            "Working dir: ${System.getProperty("user.dir")} | Using WASM dir: " +
                (wasmOut?.absolutePath ?: if (serveWebClient) "<not found>" else "<disabled by $DISABLE_WEB_VARIABLE>")
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

        devicesPage(access)

        route("/api") {
            // Sync API v1. The endpoints it replaced were deleted in T10.
            syncApiV1(store, journal)
            pairingApi(access)

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
        call.respondBytes(bytes, contentType = ContentType.Image.PNG)
    }
}
/**
 * Why [call] may not go on, as a status and a sentence; null when it may.
 *
 * Over TLS, only the sync API answers, and only to a paired device's token — pairing itself
 * excepted, which is how a device gets one. The plain port answers this computer only, which its
 * loopback binding already ensures; a browser on this computer is held to it as well, since any page
 * it has open could otherwise send requests here: the `Host` must name this computer, which stops a
 * page reaching it through a name of its own (DNS rebinding), and a change must come from a page of
 * this server's own, which stops one changing notes from another site.
 */
internal fun refusalOf(call: ApplicationCall, access: ServerAccess): Pair<HttpStatusCode, String>? {
    val path = call.request.path()
    if (access.securePort != null && call.request.local.localPort == access.securePort) {
        if (path == "/api/pair") return null
        if (!path.startsWith("/api/") || path.startsWith("/api/local/")) {
            return HttpStatusCode.NotFound to "Not found. The web client and the Devices page are on the computer the server runs on."
        }
        val token = call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim()
        return if (token.isNullOrEmpty() || access.devices.authenticate(token) == null) {
            HttpStatusCode.Unauthorized to "This device is not paired, or was revoked. Pair it again from the Devices page."
        } else {
            null
        }
    }
    if (!namesThisComputer(call.request.host())) {
        return HttpStatusCode.Forbidden to "This port serves the computer the server runs on, by the name localhost."
    }
    val method = call.request.httpMethod
    if (method != HttpMethod.Get && method != HttpMethod.Head && method != HttpMethod.Options) {
        val origin = call.request.header(HttpHeaders.Origin)?.let { runCatching { Url(it) }.getOrNull() ?: Url("null://") }
        if (origin != null && !(origin.host.equals(call.request.host(), ignoreCase = true) && origin.port == call.request.port())) {
            return HttpStatusCode.Forbidden to "Changes are accepted from this server's own pages only."
        }
    }
    return null
}

