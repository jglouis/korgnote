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
fun Route.devicesPage(access: ServerAccess) {
    get("/devices") {
        call.respondText(page(devicesBody(access)), ContentType.Text.Html)
    }
    post("/devices/pair") {
        val details = access.pairing ?: return@post call.respondText("This server does not pair devices.", status = HttpStatusCode.NotFound)
        val typed = call.receiveParameters()["addresses"]?.let(::splitAddresses).orEmpty()
        if (typed.isNotEmpty()) details.lastAddresses = typed
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
        if (given.isNotEmpty()) details.lastAddresses = given
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

private fun devicesBody(access: ServerAccess): String = buildString {
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
            append("<td><form method=post action=\"/devices/").append(escape(device.id)).append("/revoke\" ")
            append("onsubmit=\"return confirm('Revoke ").append(escape(device.name).replace("'", "&#39;"))
            append("? It will stop syncing until it is paired again.')\"><button>Revoke</button></form></td></tr>")
        }
        append("</table>")
    }
    val details = access.pairing
    if (details != null) {
        append("<h2>Pair a device</h2>")
        append("<p>Shows a QR code to scan in KorgNote's Settings. It works once, for 10 minutes.</p>")
        append("<form method=post action=/devices/pair>")
        append("<label>Addresses the phone can reach this computer at<br>")
        append("<input name=addresses size=40 value=\"").append(escape(details.addresses().joinToString(", "))).append("\"></label> ")
        append("<button>Show a pairing code</button></form>")
        append("<p class=muted>Server ").append(escape(details.serverName)).append(" · certificate ")
        append(details.fingerprint.chunked(4).take(4).joinToString(" ")).append("…</p>")
    }
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
@media (prefers-color-scheme:dark){body{color:#e6e1e5;background:#1c1b1f}th,td{border-color:#49454f}.muted{color:#938f99}}
</style></head><body>$body</body></html>"""

private fun time(epochMillis: Long): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epochMillis))

private fun escape(text: String) = text
    .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
