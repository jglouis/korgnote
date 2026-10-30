package com.korgnote.server

import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The plain port serves the computer the server runs on, and a `Host: localhost` header says
 * nothing about where a connection comes from: the caller writes it. In a container the port
 * listens on every interface inside, so another container on the same network could reach it,
 * claim to be localhost, read and write every note, and pair a device of its own.
 *
 * These connect over real sockets, from an address of this machine that is not loopback, which is
 * what such a neighbour looks like to the server.
 */
class PlainPortPeerTest {

    private val notes = Files.createTempDirectory("korgnote-peer").toFile().also {
        File(it, "a.org").writeText("* A\n")
        System.setProperty("notes.dir", it.absolutePath)
    }
    private val port = ServerSocket(0).use { it.localPort }
    private val server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> = embeddedServer(
        Netty,
        configure = { connector { host = "0.0.0.0"; port = this@PlainPortPeerTest.port } },
    ) {
        module(ServerAccess.localOnly())
    }.start(wait = false)

    @AfterTest
    fun stop() {
        server.stop(0, 0)
        notes.deleteRecursively()
    }

    /** An IPv4 address of this machine that is not loopback, or null on one with none. */
    private fun nonLoopbackAddress(): InetAddress? = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address }

    /** The status of a GET for [path], sent from [from] to [to] with `Host: localhost`. */
    private fun statusOf(path: String, from: InetAddress, to: InetAddress): Int =
        Socket().use { socket ->
            socket.bind(InetSocketAddress(from, 0))
            socket.connect(InetSocketAddress(to, port), 5_000)
            socket.soTimeout = 5_000
            socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray())
            socket.getInputStream().bufferedReader().readLine().split(' ')[1].toInt()
        }

    @Test
    fun aConnectionFromElsewhereIsRefusedThoughItSaysLocalhost() {
        val elsewhere = nonLoopbackAddress() ?: return // Nothing to connect from: nothing to show.

        assertEquals(403, statusOf("/api/v1/manifest", from = elsewhere, to = elsewhere))
        assertEquals(403, statusOf("/api/local/pairing", from = elsewhere, to = elsewhere), "nor may it pair a device")
    }

    @Test
    fun aConnectionFromThisComputerIsServed() {
        val loopback = InetAddress.getByName("127.0.0.1")
        assertEquals(200, statusOf("/api/v1/manifest", from = loopback, to = loopback))
    }

    /** `/proc/net/route` as a container has it: the default route's gateway, little-endian hex. */
    @Test
    fun theContainersGatewayIsReadFromItsRoutes() {
        val routes = """
            Iface	Destination	Gateway 	Flags	RefCnt	Use	Metric	Mask		MTU	Window	IRTT
            eth0	00000000	010011AC	0003	0	0	0	00000000	0	0	0
            eth0	000011AC	00000000	0001	0	0	0	0000FFFF	0	0	0
        """.trimIndent()
        assertEquals("172.17.0.1", defaultGateway(routes))
        assertNull(defaultGateway("Iface\tDestination\tGateway\n"))
    }
}
