package com.korgnote.app.di

import com.korgnote.app.data.CertificatePins
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFRelease
import platform.Foundation.NSURLAuthenticationMethodServerTrust
import platform.Foundation.NSURLCredential
import platform.Foundation.NSURLSessionAuthChallengeCancelAuthenticationChallenge
import platform.Foundation.NSURLSessionAuthChallengePerformDefaultHandling
import platform.Foundation.NSURLSessionAuthChallengeUseCredential
import platform.Foundation.credentialForTrust
import platform.Foundation.serverTrust
import platform.Security.SecCertificateCopyData
import platform.Security.SecTrustGetCertificateAtIndex
import platform.Security.SecTrustRef

@OptIn(ExperimentalForeignApi::class)
actual fun platformBuildHttpClient(pins: CertificatePins): HttpClient = HttpClient(Darwin) {
    // The paired computer's certificate, by its fingerprint, and no other — as PinnedTrustManager
    // does on Android and the desktop. Not yet tried on an iPhone.
    engine {
        handleChallenge { _, _, challenge, completionHandler ->
            val space = challenge.protectionSpace
            val trust = space.serverTrust
            if (space.authenticationMethod != NSURLAuthenticationMethodServerTrust || trust == null) {
                completionHandler(NSURLSessionAuthChallengePerformDefaultHandling, null)
            } else if (leafFingerprint(trust)?.let { it in pins.accepted() } == true) {
                completionHandler(NSURLSessionAuthChallengeUseCredential, NSURLCredential.credentialForTrust(trust))
            } else {
                completionHandler(NSURLSessionAuthChallengeCancelAuthenticationChallenge, null)
            }
        }
    }

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

/** SHA-256 of the server's own certificate, lower-case hex. */
@OptIn(ExperimentalForeignApi::class)
private fun leafFingerprint(trust: SecTrustRef): String? {
    val certificate = SecTrustGetCertificateAtIndex(trust, 0) ?: return null
    val data = SecCertificateCopyData(certificate) ?: return null
    try {
        val bytes = CFDataGetBytePtr(data) ?: return null
        val digest = UByteArray(CC_SHA256_DIGEST_LENGTH)
        digest.usePinned { CC_SHA256(bytes, CFDataGetLength(data).convert(), it.addressOf(0)) }
        return digest.joinToString("") { it.toString(16).padStart(2, '0') }
    } finally {
        CFRelease(data)
    }
}
