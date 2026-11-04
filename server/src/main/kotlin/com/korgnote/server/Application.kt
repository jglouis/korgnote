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

fun main() {
    val config = ServerConfig.fromEnvironment()
    val identity = ServerIdentity.loadOrCreate(config.stateDir)
    val access = ServerAccess(
        DeviceRegistry(File(config.stateDir, "devices.json")),
        securePort = config.httpsPort,
        pairing = PairingDetails(identity.serverId, config.serverName, identity.fingerprint, config.advertisedPort, config.advertisedAddresses),
        thisComputer = containerGateway(),
    )
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
        // Said at startup, where whoever started the container looks, not only when a phone fails.
        access.pairing?.missingAddressAdvice()?.let { log.warn("No address to pair phones with. $it") }
        module(config.openStore(log), access, watchFiles = true, webClient = config.findWebClient(log))
    }.start(wait = true)
}

/**
 * [access] defaults to this computer only, with no network side, because that is what the test host
 * wants; `main` passes the paired devices and the secure port.
 */
fun Application.module(
    store: FileStore,
    access: ServerAccess = ServerAccess.localOnly(),
    /**
     * Whether to watch the notes directory for external edits.
     *
     * Off by default because tests start this module dozens of times, and a file watcher per test —
     * polling the filesystem on platforms without a native backend — costs far more than the tests
     * themselves. `main` turns it on.
     */
    watchFiles: Boolean = false,
    /** The build of the wasm client to serve at `/`; see [ServerConfig.findWebClient]. */
    webClient: File? = null,
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

    // The generation counter the sync API hands out, and the watcher that keeps it honest about
    // edits the server did not make itself.
    val journal = ChangeJournal()
    // A library that is read-only has no folder to watch.
    val watcher = if (watchFiles && !store.readOnly) NotesWatcher(store.root, store, journal) else null
    watcher?.start(this)
    monitor.subscribe(ApplicationStopping) { watcher?.stop() }

    routing {
        if (webClient != null) {
            staticFiles("/", webClient, index = "index.html")
        }

        get("/favicon.png") {
            respondFavIcon()
        }
        get("/favicon.ico") {
            respondFavIcon()
        }

        // API endpoints kept under /api to avoid collision with index.html routing
        // Serve index.html at root when WASM output exists; otherwise fall back to legacy greeting
        if (webClient == null) {
            get("/") {
                call.respondText("Ktor: ${Greeting().greet()}")
            }
        }

        devicesPage(access, watcher)

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
