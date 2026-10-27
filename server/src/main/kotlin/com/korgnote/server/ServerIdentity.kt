package com.korgnote.server

import io.ktor.network.tls.certificates.buildKeyStore
import io.ktor.network.tls.certificates.saveToFile
import io.ktor.network.tls.extensions.HashAlgorithm
import io.ktor.network.tls.extensions.SignatureAlgorithm
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.security.auth.x500.X500Principal

/**
 * What makes this server itself, kept in its state directory — outside the notes, which are synced
 * and backed up elsewhere: an id that outlives its addresses, and the key and self-signed
 * certificate it serves paired devices with.
 *
 * Paired devices trust that certificate by its [fingerprint] alone, which the pairing QR code
 * carries, so there is no authority to ask, no domain to own and nothing to renew: it is valid for
 * a hundred years. Losing the directory means a new certificate, and pairing each device again.
 */
class ServerIdentity private constructor(
    val serverId: String,
    val keyStore: KeyStore,
    val keyStorePassword: String,
    /** SHA-256 of the certificate, lower-case hex: what a paired device checks the server against. */
    val fingerprint: String,
) {
    companion object {
        const val KEY_ALIAS = "korgnote"

        fun loadOrCreate(dir: File): ServerIdentity {
            dir.mkdirs()
            ownerOnly(dir)
            val serverId = readOrCreate(File(dir, "server-id")) { UUID.randomUUID().toString() }
            val password = readOrCreate(File(dir, "tls.password")) { randomHex(24) }
            val storeFile = File(dir, "tls.jks")
            val keyStore = if (storeFile.isFile) {
                KeyStore.getInstance("JKS").apply { storeFile.inputStream().use { load(it, password.toCharArray()) } }
            } else {
                buildKeyStore {
                    certificate(KEY_ALIAS) {
                        this.password = password
                        daysValid = 36_500
                        hash = HashAlgorithm.SHA256
                        sign = SignatureAlgorithm.RSA
                        keySizeInBits = 2048
                        subject = X500Principal("CN=KorgNote $serverId")
                        domains = listOf("korgnote")
                    }
                }.also {
                    it.saveToFile(storeFile, password)
                    ownerOnly(storeFile)
                }
            }
            val certificate = keyStore.getCertificate(KEY_ALIAS)
                ?: error("${storeFile.absolutePath} holds no '$KEY_ALIAS' certificate; move it away to make a new one.")
            return ServerIdentity(serverId, keyStore, password, sha256Hex(certificate.encoded))
        }

        /** The text of [file], written first by [create] when it is missing or empty. */
        private fun readOrCreate(file: File, create: () -> String): String {
            file.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            val value = create()
            file.writeText(value + "\n")
            ownerOnly(file)
            return value
        }

        /** Readable by its owner only, where the file system says who may read what. */
        private fun ownerOnly(file: File) {
            runCatching {
                Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString(if (file.isDirectory) "rwx------" else "rw-------"))
            }
        }
    }
}

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** [bytes] random bytes as hex, from the system's strong source: tokens, codes, passwords. */
internal fun randomHex(bytes: Int): String =
    ByteArray(bytes).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
