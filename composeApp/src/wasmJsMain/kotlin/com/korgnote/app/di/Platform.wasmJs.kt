package com.korgnote.app.di

import com.korgnote.app.data.CertificatePins
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json

// The page is served by the server it talks to, over the plain port on this computer: no TLS of its
// own to check, so [pins] has nothing to do here.
actual fun platformBuildHttpClient(pins: CertificatePins): HttpClient = HttpClient(Js) {
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
