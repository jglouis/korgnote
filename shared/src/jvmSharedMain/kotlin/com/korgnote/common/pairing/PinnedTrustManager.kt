package com.korgnote.common.pairing

import java.net.Socket
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager

/**
 * Trusts a server by the SHA-256 of its certificate alone: one of [accepted], which pairing took from
 * the QR code, and nothing else — not a certificate any authority signed, and not a self-signed one
 * that happens to look the same.
 *
 * It does not check the server's name, since an address on a home network is not one a certificate
 * could name; the fingerprint is the stronger check, and the only one. Being an extended trust
 * manager matters for that: it is the trust manager the JDK leaves the name check to.
 */
class PinnedTrustManager(private val accepted: () -> Set<String>) : X509ExtendedTrustManager() {

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = refuseClients()

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = refuseClients()

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = refuseClients()

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    private fun check(chain: Array<out X509Certificate>?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("The server showed no certificate.")
        if (fingerprintOf(leaf.encoded) !in accepted()) {
            throw CertificateException("The server's certificate is not the one this device paired with.")
        }
    }

    private fun refuseClients(): Nothing = throw CertificateException("This trust manager checks servers only.")

    companion object {
        fun fingerprintOf(der: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it) }

        /** A TLS context that trusts what [accepted] says, for a client engine to connect with. */
        fun sslContext(accepted: () -> Set<String>): SSLContext =
            SSLContext.getInstance("TLS").apply { init(null, arrayOf(PinnedTrustManager(accepted)), null) }
    }
}
