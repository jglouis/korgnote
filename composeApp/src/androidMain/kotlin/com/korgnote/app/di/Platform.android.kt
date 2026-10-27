package com.korgnote.app.di

import android.util.Log
import com.korgnote.app.data.CertificatePins
import com.korgnote.common.pairing.PinnedTrustManager
import javax.net.ssl.HostnameVerifier
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json

actual fun platformBuildHttpClient(pins: CertificatePins): HttpClient = HttpClient(Android) {
    // The paired computer's certificate, by its fingerprint, and no other; see PinnedTrustManager.
    // The name check is off only because that check replaces it: no address names the computer.
    val tls = PinnedTrustManager.sslContext(pins::accepted).socketFactory
    engine {
        sslManager = { connection ->
            connection.sslSocketFactory = tls
            connection.hostnameVerifier = HostnameVerifier { _, _ -> true }
        }
    }

    // JSON
    install(ContentNegotiation) { json() }

    // Timeouts to avoid indefinite hangs
    install(HttpTimeout) {
        requestTimeoutMillis = 15_000
        // Short: an address the computer is not at, as away from home, should not hold up the next.
        connectTimeoutMillis = 4_000
        socketTimeoutMillis = 15_000
    }

    // Verbose logging for debug builds (detected via reflection to avoid hard dependency)
    val isDebug = try {
        val clazz = Class.forName("com.korgnote.app.BuildConfig")
        val field = clazz.getField("DEBUG")
        field.getBoolean(null)
    } catch (_: Throwable) { false }
    if (isDebug) {
        install(Logging) {
            level = LogLevel.ALL
            logger = AndroidLogKtorLogger()
            sanitizeHeader { header ->
                // keep common headers visible; redact Authorization if used
                header.equals("Authorization", ignoreCase = true)
            }
        }
    }

    expectSuccess = false
}
private class AndroidLogKtorLogger : Logger {
    override fun log(message: String) {
        Log.d("KtorClient", message)
    }
}

