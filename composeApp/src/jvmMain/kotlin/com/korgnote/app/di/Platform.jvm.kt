package com.korgnote.app.di

import com.korgnote.app.data.CertificatePins
import com.korgnote.common.pairing.PinnedTrustManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import com.korgnote.app.data.PairingProblem
import com.korgnote.common.pairing.TcpProbe
import com.korgnote.common.pairing.probeTcp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual fun platformBuildHttpClient(pins: CertificatePins): HttpClient = HttpClient(Java) {
    // The paired computer's certificate, by its fingerprint, and no other; see PinnedTrustManager.
    engine { config { sslContext(PinnedTrustManager.sslContext(pins::accepted)) } }

    install(ContentNegotiation) { json() }

    // Installed on every platform so a hung connection cannot wait forever — and so the event
    // stream can override it per request, which needs the plugin to be present.
    install(HttpTimeout) {
        requestTimeoutMillis = 15_000
        connectTimeoutMillis = 10_000
        socketTimeoutMillis = 15_000
    }
    expectSuccess = false
}

actual fun platformBuildWebDavClient(): HttpClient = HttpClient(Java) {
    install(HttpTimeout) {
        requestTimeoutMillis = 30_000
        connectTimeoutMillis = 10_000
        socketTimeoutMillis = 30_000
    }
    expectSuccess = false
}

/** A bare TCP connection tells a refused port from no answer; see [probeTcp]. */
actual suspend fun platformProbeConnection(host: String, port: Int): PairingProblem? =
    withContext(Dispatchers.IO) {
        when (probeTcp(host, port)) {
            TcpProbe.Refused -> PairingProblem.NotListening
            TcpProbe.NoAnswer -> PairingProblem.NoAnswer
            TcpProbe.Open -> null
        }
    }
