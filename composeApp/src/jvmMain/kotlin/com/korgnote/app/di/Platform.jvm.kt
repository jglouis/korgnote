package com.korgnote.app.di

import com.korgnote.app.data.CertificatePins
import com.korgnote.common.pairing.PinnedTrustManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json

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
