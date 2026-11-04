package com.korgnote.server

import com.korgnote.common.pairing.PairRequest
import com.korgnote.common.pairing.PairResponse
import com.korgnote.common.pairing.PairingLink
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.log
import io.ktor.server.request.receive
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * `POST /api/pair`: a device exchanges the one-time code from a pairing QR code for a token of its
 * own. Reached over TLS, to the certificate the same QR code named, so the token cannot go anywhere
 * else.
 */
fun Route.pairingApi(access: ServerAccess) {
    post("/pair") {
        val details = access.pairing
        if (details == null) {
            call.respondText("This server does not pair devices.", status = HttpStatusCode.NotFound)
            return@post
        }
        val request = runCatching { call.receive<PairRequest>() }.getOrNull()
        val paired = request?.let { access.devices.pair(it) }
        if (paired == null) {
            call.respondText(
                "That pairing code is not valid any more. Show a new one on the Devices page and scan it again.",
                status = HttpStatusCode.Forbidden,
            )
            return@post
        }
        val (device, token) = paired
        call.application.log.info("Paired ${device.name} (${device.id})")
        call.respond(PairResponse(device.id, token, details.serverId, details.serverName))
    }
}

/**
 * The Devices page, on the plain port only: every paired device with a button to revoke it, and a
 * button that shows a QR code to pair another. Also `POST /api/local/pairing`, which
 * `scripts/pair.sh` calls with the host's addresses and prints the answer in the terminal.
 */
fun Route.devicesPage(access: ServerAccess, watcher: NotesWatcher? = null) {
    get("/devices") {
        call.respondText(page(devicesBody(access, watcher)), ContentType.Text.Html)
    }
    post("/devices/pair") {
        val details = access.pairing ?: return@post call.respondText("This server does not pair devices.", status = HttpStatusCode.NotFound)
        val typedText = call.receiveParameters()["addresses"].orEmpty()
        val typed = splitAddresses(typedText)
        // A code is only made for somewhere a phone can go: an address it cannot use, or none at
        // all, gets the form back with what to do, rather than a QR code that leads nowhere.
        val wrong = typed.filterNot(::isPairingAddress)
        if (wrong.isNotEmpty()) {
            val problem = "“${wrong.joinToString(", ")}” is not an address. Type addresses only, such as 192.168.1.20, separated by commas: no http://, no port."
            return@post call.respondText(page(devicesBody(access, watcher, problem, typedText)), ContentType.Text.Html, HttpStatusCode.BadRequest)
        }
        if (typed.isNotEmpty()) details.lastAddresses = typed
        details.missingAddressAdvice()?.let { advice ->
            return@post call.respondText(page(devicesBody(access, watcher, advice, typedText)), ContentType.Text.Html, HttpStatusCode.BadRequest)
        }
        val link = newLink(access, details)
        call.respondText(page(pairingBody(link)), ContentType.Text.Html)
    }
    post("/devices/{id}/revoke") {
        call.parameters["id"]?.let { id ->
            if (access.devices.revoke(id)) call.application.log.info("Revoked device $id")
        }
        call.respondRedirect("/devices")
    }
    post("/api/local/pairing") {
        val details = access.pairing ?: return@post call.respondText("This server does not pair devices.", status = HttpStatusCode.NotFound)
        val given = call.request.queryParameters["addresses"]?.let(::splitAddresses).orEmpty()
        val wrong = given.filterNot(::isPairingAddress)
        if (wrong.isNotEmpty()) {
            return@post call.respondText("Not an address: ${wrong.joinToString(", ")}\n", status = HttpStatusCode.BadRequest)
        }
        if (given.isNotEmpty()) details.lastAddresses = given
        details.missingAddressAdvice()?.let { advice ->
            return@post call.respondText(
                "$advice Or pass it here: /api/local/pairing?addresses=192.168.1.20\n",
                status = HttpStatusCode.Conflict,
            )
        }
        val link = newLink(access, details)
        val text = if (call.request.queryParameters["format"] == "link") {
            link.toUri() + "\n"
        } else {
            PairingQr.terminal(link.toUri()) +
                "\nScan it in KorgNote's Settings, under Pair with a computer, within 10 minutes.\n" +
                "Addresses: ${link.addresses.joinToString(", ")}  Port: ${link.port}\n" +
                "Or paste this link there:\n${link.toUri()}\n"
        }
        call.respondText(text)
    }
}

private fun newLink(access: ServerAccess, details: PairingDetails) = PairingLink(
    serverId = details.serverId,
    serverName = details.serverName,
    addresses = details.addresses(),
    port = details.port,
    fingerprint = details.fingerprint,
    code = access.devices.newPairingCode(),
)

private fun splitAddresses(text: String) = text.split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }

/**
 * The devices, and the form that shows a pairing code. [problem] is said above the form when the
 * last try made no code, with what was typed — [typed] — kept in the box to correct.
 */
private fun devicesBody(
    access: ServerAccess,
    watcher: NotesWatcher?,
    problem: String? = null,
    typed: String? = null,
): String = buildString {
    append("<h1>Devices</h1>")
    val devices = access.devices.all()
    if (devices.isEmpty()) {
        append("<p class=muted>No device is paired yet.</p>")
    } else {
        append("<table><tr><th>Device</th><th>Paired</th><th>Last seen</th><th></th></tr>")
        for (device in devices.sortedBy { it.pairedAt }) {
            append("<tr><td>").append(escape(device.name)).append("</td>")
            append("<td>").append(time(device.pairedAt)).append("</td>")
            append("<td>").append(device.lastSeen?.let(::time) ?: "never").append("</td>")
            // The name goes in an attribute for the page's script to read, never into script itself:
            // a browser decodes `onsubmit` before parsing it, so no escaping keeps a name in its string.
            append("<td><form method=post action=\"/devices/").append(escape(device.id)).append("/revoke\" ")
            append("data-name=\"").append(escape(device.name)).append("\"><button>Revoke</button></form></td></tr>")
        }
        append("</table>")
    }
    append(notesFolderSection(watcher?.status))
    val details = access.pairing
    if (details != null) {
        append("<h2>Pair a device</h2>")
        append("<p>Shows a QR code to scan in KorgNote's Settings. It works once, for 10 minutes.</p>")
        val warning = problem ?: details.missingAddressAdvice()
        if (warning != null) append("<p class=warning role=alert>").append(escape(warning)).append("</p>")
        append("<form method=post action=/devices/pair>")
        append("<label>Addresses the phone can reach this computer at<br>")
        append("<input name=addresses size=40 placeholder=\"192.168.1.20\" value=\"")
            .append(escape(typed ?: details.addresses().joinToString(", "))).append("\"></label> ")
        append("<button>Show a pairing code</button></form>")
        append("<p class=muted>Server ").append(escape(details.serverName)).append(" · certificate ")
        append(details.fingerprint.chunked(4).take(4).joinToString(" ")).append("…</p>")
    }
}

/**
 * Whether edits made on this computer reach the devices. A phone cannot tell: when the server stops
 * noticing them, every sync still succeeds and brings nothing. So this says it, in words, and warns
 * when anything is off.
 */
internal fun notesFolderSection(status: WatcherStatus?, now: Long = System.currentTimeMillis()): String = buildString {
    append("<h2>Notes folder</h2>")
    if (status == null) {
        append("<p class=muted>Edits made outside the server are not watched: the notes folder was not found when it started.</p>")
        return@buildString
    }
    fun warn(text: String) = append("<p class=warning role=alert>").append(text).append("</p>")

    when (status.state) {
        WatcherStatus.State.STARTING -> append("<p class=muted>Starting to watch the notes folder…</p>")
        WatcherStatus.State.NOT_RUNNING -> warn(
            "The server is not watching the notes folder" +
                (status.lastFailure?.let { ": " + escape(it) } ?: "") +
                ". Edits made on this computer will not reach the devices until the server is restarted.",
        )
        WatcherStatus.State.FAILING -> warn(
            "The server cannot watch the notes folder since " + time(status.since) + ": " +
                escape(status.lastFailure.orEmpty()) +
                ". Edits made on this computer reach the devices only when it manages to look again; " +
                "the server log has the details.",
        )
        WatcherStatus.State.WATCHING -> {
            val lastCheck = status.lastCheckAt
            // A check runs every checkEvery; three missed in a row is a watcher that is stuck.
            val stuckAfter = status.checkEvery.inWholeMilliseconds * 3 + 30_000
            if (lastCheck != null && now - lastCheck > stuckAfter) {
                warn(
                    "The notes folder has not been checked since " + time(lastCheck) +
                        ". Edits made on this computer may not reach the devices; restart the server.",
                )
            } else {
                append("<p>Watching for edits made on this computer.</p>")
            }
        }
    }
    if (status.missed > 0) {
        val one = status.missed == 1
        warn(
            "${status.missed} ${if (one) "edit" else "edits"} made on this computer ${if (one) "was" else "were"} " +
                "found only by the check that runs every ${status.checkEvery}, most recently " +
                (status.lastMissedAt?.let(::time) ?: "") +
                (if (status.lastMissedPaths.isEmpty()) "" else " (" + escape(status.lastMissedPaths.joinToString(", ")) + ")") +
                ". They reached the devices, but late: the server is missing file events. The server log has the details.",
        )
    }
    val recovered = status.state == WatcherStatus.State.WATCHING || status.state == WatcherStatus.State.STARTING
    if (recovered && status.lastFailure != null && status.lastFailureAt != null) {
        append("<p class=muted>Last problem watching it, since recovered: ").append(time(status.lastFailureAt))
            .append(", ").append(escape(status.lastFailure)).append("</p>")
    }
    append("<p class=muted>")
    append(status.notes).append(if (status.notes == 1) " note" else " notes")
    append(" in ").append(status.folders).append(if (status.folders == 1) " folder" else " folders")
    status.lastCheckAt?.let { append(" · last full check ").append(time(it)) }
    status.lastChangeAt?.let { append(" · last edit seen ").append(time(it)) }
    if (status.rebuilds > 0) {
        append(" · folders changed ").append(status.rebuilds).append(if (status.rebuilds == 1) " time" else " times")
    }
    append("</p>")
}

private fun pairingBody(link: PairingLink): String = buildString {
    append("<h1>Pair a device</h1>")
    append("<p>In KorgNote on the phone: Settings, <b>Pair with a computer</b>, <b>Scan the code</b>.</p>")
    append("<div class=qr>").append(PairingQr.svg(link.toUri())).append("</div>")
    append("<p class=muted>Valid once, for 10 minutes. Addresses: ").append(escape(link.addresses.joinToString(", ")))
    append(" · port ").append(link.port).append("</p>")
    append("<details><summary>Or paste the link instead</summary><p><code>").append(escape(link.toUri())).append("</code></p></details>")
    append("<p><a href=/devices>Back to the devices</a></p>")
}

private fun page(body: String) = """<!doctype html>
<html lang=en><head><meta charset=utf-8><meta name=viewport content="width=device-width, initial-scale=1">
<title>KorgNote devices</title>
<style>
body{font:16px/1.5 system-ui,sans-serif;max-width:44rem;margin:2rem auto;padding:0 1rem;color:#1c1b1f;background:#fffbfe}
table{border-collapse:collapse;width:100%}th,td{text-align:left;padding:.4rem .6rem;border-bottom:1px solid #ddd}
button{font:inherit;padding:.3rem .9rem;cursor:pointer}input{font:inherit;padding:.3rem}
.muted{color:#79747e}.qr svg{max-width:100%;height:auto}code{word-break:break-all}
.warning{border-left:4px solid #b3261e;padding:.5rem .8rem;background:#f9dedc;color:#410e0b}
@media (prefers-color-scheme:dark){body{color:#e6e1e5;background:#1c1b1f}th,td{border-color:#49454f}.muted{color:#938f99}.warning{background:#601410;color:#f9dedc;border-color:#f2b8b5}}
</style></head><body>$body
<script>
// The server may run without a time zone (in Docker it is UTC): show its times in the browser's.
for (const t of document.querySelectorAll("time[datetime]")) {
  const d = new Date(t.dateTime)
  if (!isNaN(d)) t.textContent = d.toLocaleString(undefined, {dateStyle: "medium", timeStyle: "medium"})
}
for (const form of document.querySelectorAll("form[data-name]")) {
  form.addEventListener("submit", event => {
    if (!confirm("Revoke " + form.dataset.name + "? It will stop syncing until it is paired again.")) event.preventDefault()
  })
}
</script></body></html>"""

/** A `<time>` the page's script shows in the browser's zone; the server's own, without script. */
private fun time(epochMillis: Long): String {
    val instant = Instant.ofEpochMilli(epochMillis)
    val shown = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(instant)
    return "<time datetime=\"$instant\">$shown</time>"
}

private fun escape(text: String) = text
    .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
