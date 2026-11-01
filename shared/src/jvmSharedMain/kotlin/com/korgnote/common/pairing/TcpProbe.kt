package com.korgnote.common.pairing

import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket

/** What a bare TCP connection to an address found there. */
enum class TcpProbe {
    /** Something accepted the connection. */
    Open,

    /** The computer is there, and turned the connection down: nothing listens on that port. */
    Refused,

    /** No answer: no such computer on this network, a firewall dropping it, or a timeout. */
    NoAnswer,
}

/**
 * Connects to [host]:[port] and hangs up, to tell why an HTTPS request there failed: Android's
 * HTTP engine words a refused connection and an absent computer alike, "Failed to connect", while
 * the socket underneath says `ECONNREFUSED` for the first. Blocking; call it off the main thread.
 */
fun probeTcp(host: String, port: Int, timeoutMillis: Int = 2_000): TcpProbe = try {
    Socket().use { it.connect(InetSocketAddress(host, port), timeoutMillis) }
    TcpProbe.Open
} catch (e: ConnectException) {
    if ("refused" in e.message.orEmpty().lowercase()) TcpProbe.Refused else TcpProbe.NoAnswer
} catch (_: IOException) {
    TcpProbe.NoAnswer
} catch (_: IllegalArgumentException) {
    TcpProbe.NoAnswer
}
